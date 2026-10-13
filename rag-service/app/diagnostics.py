"""Request correlation only; never log prompts, credentials or upstream bodies."""
from contextvars import ContextVar

request_trace_id = ContextVar('request_trace_id', default='unscoped')
