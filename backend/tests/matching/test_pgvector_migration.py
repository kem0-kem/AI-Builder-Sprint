import os
from pathlib import Path
from uuid import UUID, uuid4

import psycopg
import pytest
from alembic import command
from alembic.config import Config
from psycopg import Connection
from psycopg.errors import CheckViolation, UniqueViolation
from testcontainers.community.postgres import PostgresContainer

from app.core.config import MATCHING_EMBEDDING_DIMENSIONS, get_settings

pytestmark = pytest.mark.skipif(
    os.getenv("RUN_POSTGRES_INTEGRATION") != "1",
    reason="set RUN_POSTGRES_INTEGRATION=1 when Docker is available",
)

ALICE_ID = UUID("00000000-0000-0000-0000-000000000001")
BOB_ID = UUID("00000000-0000-0000-0000-000000000002")


def alembic_config() -> Config:
    backend_dir = Path(__file__).resolve().parents[2]
    config = Config(str(backend_dir / "alembic.ini"))
    config.set_main_option("path_separator", "os")
    return config


def scalar(connection: Connection[tuple[object, ...]], query: str) -> object:
    row = connection.execute(query).fetchone()
    assert row is not None
    return row[0]


def insert_user(connection: Connection[tuple[object, ...]], user_id: UUID, email: str) -> None:
    connection.execute(
        """
        INSERT INTO users (
            id, email, password_hash, nickname, is_active, created_at
        ) VALUES (%s, %s, 'hash', 'tester', TRUE, NOW())
        """,
        (user_id, email),
    )


def test_pgvector_migration_contract_and_round_trip(monkeypatch: pytest.MonkeyPatch) -> None:
    with PostgresContainer("pgvector/pgvector:pg16", driver="psycopg") as postgres:
        sqlalchemy_url = postgres.get_connection_url()
        psycopg_url = sqlalchemy_url.replace("+psycopg", "")
        monkeypatch.setenv("DATABASE_URL", sqlalchemy_url)
        get_settings.cache_clear()

        config = alembic_config()
        command.upgrade(config, "head")

        with psycopg.connect(psycopg_url) as connection:
            extension_count = scalar(
                connection,
                "SELECT count(*) FROM pg_extension WHERE extname = 'vector'",
            )
            assert extension_count == 1
            assert scalar(
                connection,
                """
                SELECT format_type(atttypid, atttypmod)
                FROM pg_attribute
                WHERE attrelid = 'letter_embeddings'::regclass
                  AND attname = 'embedding'
                """,
            ) == f"vector({MATCHING_EMBEDDING_DIMENSIONS})"

            index_definitions = "\n".join(
                row[0]
                for row in connection.execute(
                    """
                    SELECT indexdef
                    FROM pg_indexes
                    WHERE indexname IN (
                        'ix_letter_embeddings_embedding_hnsw',
                        'ix_user_match_vectors_embedding_hnsw'
                    )
                    ORDER BY indexname
                    """
                ).fetchall()
            )
            assert index_definitions.count("USING hnsw") == 2
            assert index_definitions.count("vector_cosine_ops") == 2

            constraint_definitions = "\n".join(
                row[0]
                for row in connection.execute(
                    """
                    SELECT pg_get_constraintdef(oid)
                    FROM pg_constraint
                    WHERE conrelid = 'match_history'::regclass
                    """
                ).fetchall()
            )
            assert "CHECK ((user_a_id < user_b_id))" in constraint_definitions
            assert "UNIQUE (user_a_id, user_b_id)" in constraint_definitions

            insert_user(connection, ALICE_ID, "alice@example.com")
            insert_user(connection, BOB_ID, "bob@example.com")
            connection.execute(
                """
                INSERT INTO match_history (
                    id, user_a_id, user_b_id, strategy, created_at
                ) VALUES (%s, %s, %s, 'PROFILE', NOW())
                """,
                (uuid4(), ALICE_ID, BOB_ID),
            )
            connection.commit()

            with pytest.raises(UniqueViolation):
                connection.execute(
                    """
                    INSERT INTO match_history (
                        id, user_a_id, user_b_id, strategy, created_at
                    ) VALUES (%s, %s, %s, 'SEMANTIC', NOW())
                    """,
                    (uuid4(), ALICE_ID, BOB_ID),
                )
            connection.rollback()

            with pytest.raises(CheckViolation):
                connection.execute(
                    """
                    INSERT INTO match_history (
                        id, user_a_id, user_b_id, strategy, created_at
                    ) VALUES (%s, %s, %s, 'SEMANTIC', NOW())
                    """,
                    (uuid4(), BOB_ID, ALICE_ID),
                )
            connection.rollback()

        command.downgrade(config, "0003_moderation_review_lifecycle")
        with psycopg.connect(psycopg_url) as connection:
            assert scalar(connection, "SELECT to_regclass('match_history')") is None
            extension_count = scalar(
                connection,
                "SELECT count(*) FROM pg_extension WHERE extname = 'vector'",
            )
            assert extension_count == 1

        command.upgrade(config, "head")
        with psycopg.connect(psycopg_url) as connection:
            assert scalar(connection, "SELECT to_regclass('user_match_vectors')") is not None

    get_settings.cache_clear()
