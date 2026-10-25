"""Config flow for TV Camera Overlay.

No fields to collect here — which TV, which camera/photo/message, and which position are all
picked per service call instead (see services.yaml), so a single install of this integration
covers every TV running the companion Android app. This flow exists purely so the integration can
be added through Settings -> Devices & Services -> Add Integration instead of YAML.
"""

from __future__ import annotations

import voluptuous as vol

from homeassistant import config_entries
from homeassistant.config_entries import ConfigFlowResult

from .const import DOMAIN


class TvCamConfigFlow(config_entries.ConfigFlow, domain=DOMAIN):
    """Single-step, no-input confirmation flow."""

    VERSION = 1

    async def async_step_user(self, user_input: dict | None = None) -> ConfigFlowResult:
        if self._async_current_entries():
            return self.async_abort(reason="single_instance_allowed")

        if user_input is not None:
            return self.async_create_entry(title="TV Camera Overlay", data={})

        return self.async_show_form(step_id="user", data_schema=vol.Schema({}))
