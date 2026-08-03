from datetime import UTC, datetime, timedelta
from typing import Annotated
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, status
from fastapi.responses import JSONResponse
from sqlalchemy import select

from app.ai.gateway import WritingAssistantGateway, WritingContext, get_writing_assistant
from app.auth.dependencies import CurrentUserId, Session
from app.common.responses import page, success
from app.core.errors import ApiError
from app.core.rate_limit import report_limiter
from app.moderation.dependencies import MODERATION_RESPONSES, Moderation, moderation_response
from app.moderation.repository import ModerationCommand
from app.moderation.schemas import ContentType
from app.reports.models import AnalysisSnapshot, ReflectionReport
from app.reports.schemas import ReportAnalyzeRequest, ReportCreate
from app.reports.service import ReportCommandHandler, source_hash

router = APIRouter(prefix="/reports", tags=["reports"])
Assistant = Annotated[WritingAssistantGateway, Depends(get_writing_assistant)]


def report_view(report: ReflectionReport) -> dict[str, object]:
    return {
        "id": str(report.id),
        "content": report.content,
        "summary": report.summary,
        "feedback": report.feedback,
        "createdAt": report.created_at.isoformat(),
    }


@router.post(
    "/feedback",
    status_code=status.HTTP_201_CREATED,
    dependencies=[Depends(report_limiter)],
)
async def analyze_report(
    request: ReportAnalyzeRequest,
    user_id: CurrentUserId,
    session: Session,
    assistant: Assistant,
) -> dict[str, object]:
    try:
        result = await assistant.feedback(WritingContext.REPORT, None, request.content)
    except TimeoutError as exc:
        raise ApiError(
            "AI_SERVICE_UNAVAILABLE", "AI 글 분석 서비스를 사용할 수 없습니다.", 503
        ) from exc
    cards = [
        {"type": "TODAY_LEARNING", "content": result.suggestions[0]},
        {"type": "TOMORROW_PROMISE", "content": "내일 실천할 작은 행동을 정해 보세요."},
        {"type": "ONE_LINE_RECORD", "content": result.summary},
    ]
    snapshot = AnalysisSnapshot(
        owner_id=user_id,
        source_hash=source_hash(request.content),
        summary=result.summary,
        feedback=cards,
        expires_at=datetime.now(UTC) + timedelta(minutes=30),
    )
    session.add(snapshot)
    await session.commit()
    return success({"analysisId": str(snapshot.id), "summary": snapshot.summary, "feedback": cards})


@router.post(
    "",
    status_code=status.HTTP_201_CREATED,
    dependencies=[Depends(report_limiter)],
    response_model=None,
    responses=MODERATION_RESPONSES,
)
async def create_report(
    request: ReportCreate,
    user_id: CurrentUserId,
    session: Session,
    moderation: Moderation,
) -> dict[str, object] | JSONResponse:
    snapshot = await session.get(AnalysisSnapshot, request.analysis_id)
    now = datetime.now(UTC)
    expires_at = snapshot.expires_at.replace(tzinfo=UTC) if snapshot else now
    if snapshot is None or snapshot.owner_id != user_id:
        raise ApiError("RESOURCE_NOT_FOUND", "분석 결과를 찾을 수 없습니다.", 404)
    if snapshot.consumed_at is not None or expires_at <= now:
        raise ApiError("RESOURCE_CONFLICT", "분석 결과가 만료되었거나 이미 사용되었습니다.", 409)
    if snapshot.source_hash != source_hash(request.content):
        raise ApiError("RESOURCE_CONFLICT", "분석한 원문과 저장할 원문이 다릅니다.", 409)
    payload = request.model_dump(by_alias=True, mode="json")
    key = str(uuid4())
    if moderation is not None:
        outcome = await moderation.evaluate(
            ModerationCommand(
                owner_id=user_id,
                content_type=ContentType.REPORT,
                operation="CREATE_REPORT",
                text=request.content,
                payload=payload,
                idempotency_key=key,
            )
        )
        response = await moderation_response(outcome, session)
        if response is not None:
            return response
    result = await ReportCommandHandler(session).create_report(user_id, payload, key)
    return success(report_view(result.report))


@router.get("")
async def list_reports(user_id: CurrentUserId, session: Session) -> dict[str, object]:
    reports = (
        await session.execute(
            select(ReflectionReport)
            .where(ReflectionReport.owner_id == user_id)
            .order_by(ReflectionReport.created_at.desc())
        )
    ).scalars()
    return page([report_view(item) for item in reports], next_cursor=None)


@router.get("/{report_id}")
async def get_report(
    report_id: UUID, user_id: CurrentUserId, session: Session
) -> dict[str, object]:
    report = await session.get(ReflectionReport, report_id)
    if report is None or report.owner_id != user_id:
        raise ApiError("RESOURCE_NOT_FOUND", "회고를 찾을 수 없습니다.", 404)
    return success(report_view(report))
