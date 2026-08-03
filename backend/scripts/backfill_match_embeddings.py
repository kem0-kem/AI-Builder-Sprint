"""Run a bounded page of historical matching embedding projections."""

import argparse
import asyncio
import json
from uuid import UUID

import httpx

from app.core.config import get_settings
from app.db.session import SessionFactory
from app.matching.backfill import (
    BackfillCommandError,
    MatchEmbeddingBackfill,
    SqlAlchemyBackfillRepository,
)
from app.matching.embedding_worker import EmbeddingProjectionRepository, LetterEmbeddingWorker
from app.matching.upstage_gateway import UpstageEmbeddingGateway


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Backfill delivered-letter embeddings")
    parser.add_argument("--after", type=UUID)
    parser.add_argument("--limit", type=int, default=100)
    return parser.parse_args()


async def run(after: UUID | None, limit: int) -> dict[str, object]:
    settings = get_settings()
    if settings.upstage_api_key is None or not settings.upstage_embedding_model:
        raise BackfillCommandError("matching embedding configuration is not ready")
    assert settings.upstage_api_key is not None
    assert settings.upstage_embedding_model is not None

    async with SessionFactory() as session, httpx.AsyncClient(
        base_url=str(settings.upstage_base_url), timeout=10.0
    ) as client:
        worker = LetterEmbeddingWorker(
            EmbeddingProjectionRepository(session),
            UpstageEmbeddingGateway(
                client=client,
                api_key=settings.upstage_api_key,
                model=settings.upstage_embedding_model,
                expected_dimensions=settings.embedding_dimensions,
            ),
            model_name=settings.upstage_embedding_model,
            model_version=settings.upstage_embedding_model,
        )
        page = await MatchEmbeddingBackfill(
            SqlAlchemyBackfillRepository(session),
            worker,
            commit_batch=session.commit,
            rollback_batch=session.rollback,
        ).run(after=after, limit=limit)
    return {
        "processed": page.processed,
        "nextCursor": str(page.next_cursor) if page.next_cursor is not None else None,
        "exhausted": page.exhausted,
    }


def main() -> None:
    args = parse_args()
    try:
        result = asyncio.run(run(args.after, args.limit))
    except BackfillCommandError as error:
        raise SystemExit(str(error)) from None
    except Exception:
        # Provider and database exceptions can contain request bodies.  Keep the
        # operator output bounded; application monitoring has the safe context.
        raise SystemExit("backfill failed") from None
    print(json.dumps(result, separators=(",", ":")))


if __name__ == "__main__":
    main()
