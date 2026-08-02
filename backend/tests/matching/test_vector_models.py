from io import StringIO
from pathlib import Path
from typing import cast
from uuid import UUID

import pytest
from alembic import command
from alembic.config import Config
from alembic.script import ScriptDirectory
from pgvector.sqlalchemy import Vector
from pydantic import ValidationError

from app.core.config import MATCHING_EMBEDDING_DIMENSIONS, Settings
from app.matching.models import (
    LetterEmbedding,
    MatchHistory,
    MatchStrategy,
    UserMatchVector,
)

ALICE_ID = UUID("00000000-0000-0000-0000-000000000001")
BOB_ID = UUID("00000000-0000-0000-0000-000000000002")


def render_migration_sql() -> str:
    backend_dir = Path(__file__).resolve().parents[2]
    output = StringIO()
    config = Config(str(backend_dir / "alembic.ini"), output_buffer=output)
    config.set_main_option("path_separator", "os")
    command.upgrade(config, "head", sql=True)
    return output.getvalue()


def render_downgrade_sql() -> str:
    backend_dir = Path(__file__).resolve().parents[2]
    output = StringIO()
    config = Config(str(backend_dir / "alembic.ini"), output_buffer=output)
    config.set_main_option("path_separator", "os")
    command.downgrade(config, "0004_matching_vectors:0003_moderation_review_lifecycle", sql=True)
    return output.getvalue()


def alembic_config() -> Config:
    backend_dir = Path(__file__).resolve().parents[2]
    config = Config(str(backend_dir / "alembic.ini"))
    config.set_main_option("path_separator", "os")
    return config


def test_matching_models_use_configured_vector_dimension() -> None:
    settings = Settings(_env_file=None)

    letter_type = cast(Vector, LetterEmbedding.__table__.c.embedding.type)
    user_type = cast(Vector, UserMatchVector.__table__.c.embedding.type)

    assert letter_type.dim == settings.embedding_dimensions
    assert user_type.dim == settings.embedding_dimensions


def test_embedding_dimension_is_fixed_to_solar_embedding_2_contract() -> None:
    assert Settings(_env_file=None).embedding_dimensions == MATCHING_EMBEDDING_DIMENSIONS
    with pytest.raises(ValidationError):
        Settings(_env_file=None, embedding_dimensions=4096)


def test_matching_migration_is_the_only_linear_head() -> None:
    scripts = ScriptDirectory.from_config(alembic_config())
    revision = scripts.get_revision("0004_matching_vectors")

    assert scripts.get_heads() == ["0004_matching_vectors"]
    assert revision is not None
    assert revision.down_revision == "0003_moderation_review_lifecycle"
    assert revision.module.EMBEDDING_DIMENSIONS == MATCHING_EMBEDDING_DIMENSIONS


def test_match_history_create_canonicalizes_user_pair() -> None:
    history = MatchHistory.create(BOB_ID, ALICE_ID, MatchStrategy.SEMANTIC)

    assert history.user_a_id == ALICE_ID
    assert history.user_b_id == BOB_ID


def test_match_history_rejects_self_match() -> None:
    with pytest.raises(ValueError, match="cannot be matched"):
        MatchHistory.create(ALICE_ID, ALICE_ID, MatchStrategy.PROFILE)


def test_vector_foreign_keys_cascade_and_are_indexed() -> None:
    letter_foreign_key = next(iter(LetterEmbedding.__table__.c.letter_id.foreign_keys))
    owner_foreign_key = next(iter(LetterEmbedding.__table__.c.owner_id.foreign_keys))
    user_foreign_key = next(iter(UserMatchVector.__table__.c.user_id.foreign_keys))

    assert letter_foreign_key.ondelete == "CASCADE"
    assert owner_foreign_key.ondelete == "CASCADE"
    assert user_foreign_key.ondelete == "CASCADE"
    assert LetterEmbedding.__table__.c.owner_id.index is True


def test_matching_migration_is_linear_and_contains_vector_contract() -> None:
    sql = render_migration_sql()

    assert "CREATE EXTENSION IF NOT EXISTS vector" in sql
    assert "CREATE TABLE match_history" in sql
    assert "uq_match_history_canonical_pair" in sql
    assert "CHECK (user_a_id < user_b_id)" in sql
    assert "CREATE TABLE letter_embeddings" in sql
    assert "CREATE TABLE user_match_vectors" in sql
    assert f"CHECK (dimensions = {MATCHING_EMBEDDING_DIMENSIONS})" in sql
    assert sql.count("vector_cosine_ops") == 2
    assert sql.count("USING hnsw") == 2


def test_matching_downgrade_keeps_shared_vector_extension() -> None:
    sql = render_downgrade_sql()

    assert "DROP TABLE user_match_vectors" in sql
    assert "DROP TABLE letter_embeddings" in sql
    assert "DROP TABLE match_history" in sql
    assert "DROP EXTENSION" not in sql
