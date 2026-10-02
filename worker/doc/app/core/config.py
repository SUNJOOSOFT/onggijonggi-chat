from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(extra="ignore")

    internal_api_key: str = Field(min_length=1)
    seaweed_filer_url: str = "http://seaweedfs-filer:8888"
    seaweed_root_prefix: str = "documents"
    max_output_bytes: int = 26_214_400
    max_source_bytes: int = Field(default=10_485_760, ge=1, le=104_857_600)
    render_timeout_seconds: int = 30
