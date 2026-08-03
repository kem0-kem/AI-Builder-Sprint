import hashlib
import unicodedata
from dataclasses import dataclass
from datetime import UTC, datetime
from uuid import UUID

from sqlalchemy import and_, select

from app.auth.dependencies import Session
from app.core.errors import ApiError
from app.letters.models import IdempotencyRecord
from app.reports.models import AnalysisSnapshot, ReflectionReport
from app.reports.schemas import ReportCreate


def source_hash(content: str) -> str:
    normalized = unicodedata.normalize("NFC", content).strip().replace("\r\n", "\n")
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


@dataclass(frozen=True, slots=True)
class ReportResult:
    resource_id: UUID
    report: ReflectionReport


class ReportCommandHandler:
    def __init__(self, session: Session) -> None:
        self._session = session

    async def create_report(
        self, owner_id: UUID, payload: dict[str, object], key: str
    ) -> ReportResult:
        prior = await self._session.scalar(
            select(IdempotencyRecord).where(
                and_(IdempotencyRecord.user_id == owner_id, IdempotencyRecord.key == key)
            )
        )
        if prior is not None:
            resource_id = prior.response.get("resourceId")
            if prior.response.get("operation") != "CREATE_REPORT" or not isinstance(
                resource_id, str
            ):
                raise ApiError("RESOURCE_CONFLICT", "저장된 리포트 결과가 올바르지 않습니다.", 409)
            report = await self._session.get(ReflectionReport, UUID(resource_id))
            if report is None or report.owner_id != owner_id:
                raise ApiError("RESOURCE_CONFLICT", "저장된 리포트 결과가 없습니다.", 409)
            return ReportResult(report.id, report)

        request = ReportCreate.model_validate(payload)
        snapshot = await self._session.get(AnalysisSnapshot, request.analysis_id)
        now = datetime.now(UTC)
        expires_at = snapshot.expires_at.replace(tzinfo=UTC) if snapshot else now
        if snapshot is None or snapshot.owner_id != owner_id:
            raise ApiError("RESOURCE_NOT_FOUND", "분석 결과를 찾을 수 없습니다.", 404)
        if snapshot.consumed_at is not None or expires_at <= now:
            raise ApiError("RESOURCE_CONFLICT", "분석 결과가 만료되었거나 사용되었습니다.", 409)
        if snapshot.source_hash != source_hash(request.content):
            raise ApiError("RESOURCE_CONFLICT", "분석 원문과 저장할 원문이 다릅니다.", 409)
        report = ReflectionReport(
            owner_id=owner_id,
            content=request.content,
            summary=snapshot.summary,
            feedback=snapshot.feedback,
        )
        snapshot.consumed_at = now
        self._session.add(report)
        await self._session.flush()
        self._session.add(
            IdempotencyRecord(
                user_id=owner_id,
                key=key,
                response={"operation": "CREATE_REPORT", "resourceId": str(report.id)},
            )
        )
        await self._session.commit()
        return ReportResult(report.id, report)
