"""Admin-only storage management routes. No cloud credentials in responses."""
from fastapi import APIRouter, Depends
from pydantic import BaseModel, ConfigDict, Field


class StorageInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str | None = Field(None, max_length=80)
    provider: str | None = None
    local_path: str | None = Field(None, max_length=1024)
    endpoint: str | None = Field(None, max_length=1024)
    region: str | None = Field(None, max_length=50)
    bucket: str | None = Field(None, max_length=63)
    prefix: str | None = Field(None, max_length=600)
    addressing_style: str | None = None
    access_key_id: str | None = Field(None, max_length=8192)
    secret_access_key: str | None = Field(None, max_length=8192)
    session_token: str | None = Field(None, max_length=8192)


def build_router(manager, require_admin):
    router = APIRouter(prefix="/api/storage", tags=["storage"], dependencies=[Depends(require_admin)])

    @router.get("")
    def list_storage():
        return manager.list_configs()

    @router.post("", status_code=201)
    def add_storage(body: StorageInput):
        return manager.save_config(body.model_dump(exclude_unset=True))

    @router.put("/{storage_id}")
    def update_storage(storage_id: str, body: StorageInput):
        return manager.save_config(body.model_dump(exclude_unset=True), storage_id)

    @router.post("/{storage_id}/test")
    def test_storage(storage_id: str):
        return manager.test_config(storage_id)

    @router.post("/{storage_id}/default")
    def default_storage(storage_id: str):
        return manager.set_default(storage_id)

    @router.delete("/{storage_id}")
    def delete_storage(storage_id: str):
        return manager.delete_config(storage_id)

    return router
