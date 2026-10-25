"""TV Camera Overlay — shows a live camera, a photo, and/or a message on top of whatever's already
playing on an Android TV running the companion `camera-overlay` app, without switching the TV away
from its current program.

This integration does not talk to the TV directly — it builds an `am broadcast` shell command and
forwards it through the already-configured `androidtv` integration's `adb_command` service, the
same mechanism used throughout this project. All it adds is a friendly HA-native service (device
picker + fields) instead of hand-written adb syntax.
"""

from __future__ import annotations

import logging

import voluptuous as vol

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import ATTR_ENTITY_ID, Platform
from homeassistant.core import HomeAssistant, ServiceCall
import homeassistant.helpers.config_validation as cv
from homeassistant.helpers.network import NoURLAvailableError, get_url

from .const import (
    ACTION_HIDE,
    ACTION_SHOW,
    ATTR_CAMERA_ENTITY,
    ATTR_CHIME,
    ATTR_DURATION,
    ATTR_ICON_SIZE,
    ATTR_ICON_URL,
    ATTR_IMAGE_PATH,
    ATTR_IMAGE_URL,
    ATTR_LOADING_OPACITY,
    ATTR_MARGIN,
    ATTR_MESSAGE,
    ATTR_MUTED,
    ATTR_OK_RESPONSE_IMAGE_PATH,
    ATTR_OK_RESPONSE_IMAGE_URL,
    ATTR_OK_RESPONSE_MESSAGE,
    ATTR_OK_WEBHOOK_ID,
    ATTR_POSITION,
    ATTR_ROUNDED_CORNERS,
    ATTR_SIZE,
    ATTR_SLOT,
    ATTR_SOUND_URL,
    ATTR_TEXT_BACKGROUND_OPACITY,
    ATTR_TEXT_LAYOUT,
    ATTR_TEXT_POSITION,
    ATTR_TITLE,
    ATTR_VIDEO_URL,
    DEFAULT_DURATION,
    DEFAULT_ICON_SIZE,
    DEFAULT_LOADING_OPACITY,
    DEFAULT_MARGIN,
    DEFAULT_POSITION,
    DEFAULT_ROUNDED_CORNERS,
    DEFAULT_SIZE,
    DEFAULT_TEXT_BACKGROUND_OPACITY,
    DEFAULT_TEXT_LAYOUT,
    DEFAULT_TEXT_POSITION,
    DOMAIN,
    PACKAGE_NAME,
    POSITIONS,
    SERVICE_HIDE,
    SERVICE_SHOW,
    SERVICE_TEST,
    SIZES,
    TEST_DURATION,
    TEST_MESSAGE,
    TEXT_LAYOUTS,
    TEXT_POSITIONS,
)

_LOGGER = logging.getLogger(__name__)

PLATFORMS: list[Platform] = []

SHOW_SCHEMA = vol.Schema(
    {
        vol.Required(ATTR_ENTITY_ID): cv.entity_ids,
        vol.Optional(ATTR_CAMERA_ENTITY): cv.entity_id,
        vol.Optional(ATTR_VIDEO_URL): cv.string,
        vol.Optional(ATTR_IMAGE_URL): cv.string,
        vol.Optional(ATTR_IMAGE_PATH): cv.string,
        vol.Optional(ATTR_TITLE): cv.string,
        vol.Optional(ATTR_MESSAGE): cv.string,
        vol.Optional(ATTR_ICON_URL): cv.string,
        vol.Optional(ATTR_ICON_SIZE, default=DEFAULT_ICON_SIZE): vol.All(vol.Coerce(int), vol.Range(min=8, max=256)),
        vol.Optional(ATTR_DURATION, default=DEFAULT_DURATION): vol.All(vol.Coerce(float), vol.Range(min=0, max=300)),
        vol.Optional(ATTR_POSITION, default=DEFAULT_POSITION): vol.In(POSITIONS),
        vol.Optional(ATTR_SIZE, default=DEFAULT_SIZE): vol.In(SIZES),
        vol.Optional(ATTR_SOUND_URL): cv.string,
        vol.Optional(ATTR_CHIME, default=False): cv.boolean,
        vol.Optional(ATTR_LOADING_OPACITY, default=DEFAULT_LOADING_OPACITY): vol.All(
            vol.Coerce(int), vol.Range(min=0, max=100),
        ),
        vol.Optional(ATTR_TEXT_POSITION, default=DEFAULT_TEXT_POSITION): vol.In(TEXT_POSITIONS),
        vol.Optional(ATTR_TEXT_LAYOUT, default=DEFAULT_TEXT_LAYOUT): vol.In(TEXT_LAYOUTS),
        vol.Optional(ATTR_TEXT_BACKGROUND_OPACITY, default=DEFAULT_TEXT_BACKGROUND_OPACITY): vol.All(
            vol.Coerce(int), vol.Range(min=0, max=100),
        ),
        vol.Optional(ATTR_ROUNDED_CORNERS, default=DEFAULT_ROUNDED_CORNERS): cv.boolean,
        vol.Optional(ATTR_OK_WEBHOOK_ID): cv.string,
        vol.Optional(ATTR_OK_RESPONSE_MESSAGE): cv.string,
        vol.Optional(ATTR_OK_RESPONSE_IMAGE_URL): cv.string,
        vol.Optional(ATTR_OK_RESPONSE_IMAGE_PATH): cv.string,
        vol.Optional(ATTR_MUTED, default=False): cv.boolean,
        vol.Optional(ATTR_SLOT): cv.string,
        vol.Optional(ATTR_MARGIN, default=DEFAULT_MARGIN): vol.All(vol.Coerce(int), vol.Range(min=0, max=200)),
    },
)

HIDE_SCHEMA = vol.Schema(
    {
        vol.Required(ATTR_ENTITY_ID): cv.entity_ids,
        vol.Optional(ATTR_SLOT): cv.string,
    },
)
TEST_SCHEMA = vol.Schema({vol.Required(ATTR_ENTITY_ID): cv.entity_ids})


def _shell_quote(value: str) -> str:
    """POSIX single-quote escaping — safe against spaces and any shell-special characters in a
    message/URL, since this ends up inside a shell command string sent to the TV via adb."""
    return "'" + value.replace("'", "'\"'\"'") + "'"


def _build_command(
    action: str,
    *,
    camera_entity: str | None = None,
    video_url: str | None = None,
    image_url: str | None = None,
    title: str | None = None,
    message: str | None = None,
    duration: float | None = None,
    position: str | None = None,
    size: str | None = None,
    sound_url: str | None = None,
    chime: bool = False,
    loading_opacity: int | None = None,
    text_position: str | None = None,
    text_layout: str | None = None,
    text_background_opacity: int | None = None,
    rounded_corners: bool | None = None,
    ok_webhook_url: str | None = None,
    ok_response_message: str | None = None,
    ok_response_image_url: str | None = None,
    muted: bool = False,
    slot: str | None = None,
    margin: int | None = None,
    icon_url: str | None = None,
    icon_size: int | None = None,
) -> str:
    parts = ["am", "broadcast", "--include-stopped-packages", "-p", PACKAGE_NAME, "-a", action]
    if camera_entity:
        parts += ["--es", "entity_id", _shell_quote(camera_entity)]
    if video_url:
        parts += ["--es", "video_url", _shell_quote(video_url)]
    if image_url:
        parts += ["--es", "image_url", _shell_quote(image_url)]
    if title:
        parts += ["--es", "title", _shell_quote(title)]
    if message:
        parts += ["--es", "message", _shell_quote(message)]
    if position:
        parts += ["--es", "position", _shell_quote(position)]
    if size:
        parts += ["--es", "size", _shell_quote(size)]
    if sound_url:
        parts += ["--es", "sound_url", _shell_quote(sound_url)]
    if chime:
        parts += ["--ez", "chime", "true"]
    if loading_opacity is not None:
        parts += ["--ei", "loading_opacity", str(int(loading_opacity))]
    if text_position:
        parts += ["--es", "text_position", _shell_quote(text_position)]
    if text_layout:
        parts += ["--es", "text_layout", _shell_quote(text_layout)]
    if text_background_opacity is not None:
        parts += ["--ei", "text_background_opacity", str(int(text_background_opacity))]
    if rounded_corners is not None:
        parts += ["--ez", "rounded_corners", "true" if rounded_corners else "false"]
    if ok_webhook_url:
        parts += ["--es", "ok_webhook_url", _shell_quote(ok_webhook_url)]
    if ok_response_message:
        parts += ["--es", "ok_response_message", _shell_quote(ok_response_message)]
    if ok_response_image_url:
        parts += ["--es", "ok_response_image_url", _shell_quote(ok_response_image_url)]
    if muted:
        parts += ["--ez", "muted", "true"]
    if slot:
        parts += ["--es", "slot", _shell_quote(slot)]
    if margin is not None:
        parts += ["--ei", "margin", str(int(margin))]
    if icon_url:
        parts += ["--es", "icon_url", _shell_quote(icon_url)]
        if icon_size is not None:
            parts += ["--ei", "icon_size", str(int(icon_size))]
    if duration:
        parts += ["--el", "duration_ms", str(int(duration * 1000))]
    return " ".join(parts)


def _build_webhook_url(hass: HomeAssistant, webhook_id: str) -> str | None:
    """The TV app calls this URL directly over the LAN — never hass.io/Nabu Casa's external/cloud
    URL, which the TV either can't reach or would route needlessly out and back through the
    internet. None (with a logged warning) if no internal URL is configured at all, so a missing
    ok_webhook_id config just quietly drops that one field rather than failing the whole SHOW."""
    try:
        base = get_url(hass, allow_external=False, allow_cloud=False)
    except NoURLAvailableError:
        _LOGGER.warning("tvcam: no internal Home Assistant URL configured; ok_webhook_id ignored")
        return None
    return f"{base}/api/webhook/{webhook_id}"


def _local_url_from_config_path(hass: HomeAssistant, path: str) -> str | None:
    """Turns a local file path under the HA config's www/ folder (the only folder HA serves
    publicly, at the /local/ URL prefix — anything outside it isn't web-accessible without extra
    HA configuration this integration can't assume) into a URL the TV app can actually fetch.
    Accepts /config/www/..., config/www/..., or a bare www/... path; None (with a logged warning)
    for anything else or if no internal HA URL is configured, so a bad/unsupported image_path just
    quietly drops that one field rather than failing the whole SHOW."""
    normalized = path.strip().lstrip("/")
    for prefix in ("config/www/", "www/"):
        if normalized.startswith(prefix):
            rest = normalized[len(prefix):]
            break
    else:
        _LOGGER.warning(
            "tvcam: image_path %r is not under config/www — only files HA serves publicly "
            "(config/www/...) can be shown this way; ignoring",
            path,
        )
        return None
    try:
        base = get_url(hass, allow_external=False, allow_cloud=False)
    except NoURLAvailableError:
        _LOGGER.warning("tvcam: no internal Home Assistant URL configured; image_path ignored")
        return None
    return f"{base}/local/{rest}"


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    async def _send(entity_ids: list[str], command: str) -> None:
        for target in entity_ids:
            await hass.services.async_call(
                "androidtv",
                "adb_command",
                {ATTR_ENTITY_ID: target, "command": command},
                blocking=True,
            )

    async def handle_show(call: ServiceCall) -> None:
        camera_entity = call.data.get(ATTR_CAMERA_ENTITY)
        video_url = call.data.get(ATTR_VIDEO_URL)
        image_url = call.data.get(ATTR_IMAGE_URL)
        image_path = call.data.get(ATTR_IMAGE_PATH)
        if not image_url and image_path:
            image_url = _local_url_from_config_path(hass, image_path)
        title = call.data.get(ATTR_TITLE)
        message = call.data.get(ATTR_MESSAGE)
        sound_url = call.data.get(ATTR_SOUND_URL)
        chime = call.data.get(ATTR_CHIME, False)
        icon_url = call.data.get(ATTR_ICON_URL)
        if not camera_entity and not video_url and not image_url and not title and not message and not icon_url and not sound_url and not chime:
            _LOGGER.warning(
                "tvcam.show called with no camera_entity/video_url/image_url/title/message/icon_url/sound_url/chime — nothing to show",
            )
            return
        ok_webhook_id = call.data.get(ATTR_OK_WEBHOOK_ID)
        ok_webhook_url = _build_webhook_url(hass, ok_webhook_id) if ok_webhook_id else None
        ok_response_image_url = call.data.get(ATTR_OK_RESPONSE_IMAGE_URL)
        ok_response_image_path = call.data.get(ATTR_OK_RESPONSE_IMAGE_PATH)
        if not ok_response_image_url and ok_response_image_path:
            ok_response_image_url = _local_url_from_config_path(hass, ok_response_image_path)
        command = _build_command(
            ACTION_SHOW,
            camera_entity=camera_entity,
            video_url=video_url,
            image_url=image_url,
            title=title,
            message=message,
            duration=call.data.get(ATTR_DURATION, DEFAULT_DURATION),
            position=call.data.get(ATTR_POSITION, DEFAULT_POSITION),
            size=call.data.get(ATTR_SIZE, DEFAULT_SIZE),
            sound_url=sound_url,
            chime=chime,
            loading_opacity=call.data.get(ATTR_LOADING_OPACITY, DEFAULT_LOADING_OPACITY),
            text_position=call.data.get(ATTR_TEXT_POSITION, DEFAULT_TEXT_POSITION),
            text_layout=call.data.get(ATTR_TEXT_LAYOUT, DEFAULT_TEXT_LAYOUT),
            text_background_opacity=call.data.get(ATTR_TEXT_BACKGROUND_OPACITY, DEFAULT_TEXT_BACKGROUND_OPACITY),
            rounded_corners=call.data.get(ATTR_ROUNDED_CORNERS, DEFAULT_ROUNDED_CORNERS),
            ok_webhook_url=ok_webhook_url,
            ok_response_message=call.data.get(ATTR_OK_RESPONSE_MESSAGE),
            ok_response_image_url=ok_response_image_url,
            muted=call.data.get(ATTR_MUTED, False),
            slot=call.data.get(ATTR_SLOT),
            margin=call.data.get(ATTR_MARGIN, DEFAULT_MARGIN),
            icon_url=icon_url,
            icon_size=call.data.get(ATTR_ICON_SIZE, DEFAULT_ICON_SIZE),
        )
        await _send(call.data[ATTR_ENTITY_ID], command)

    async def handle_hide(call: ServiceCall) -> None:
        command = _build_command(ACTION_HIDE, slot=call.data.get(ATTR_SLOT))
        await _send(call.data[ATTR_ENTITY_ID], command)

    async def handle_test(call: ServiceCall) -> None:
        # A real end-to-end check, not just "is adb_command reachable" — this is what actually
        # matters: did the whole HA -> adb -> broadcast -> receiver -> service -> overlay window
        # chain work, visibly, on the TV itself. A canned centered message + chime, auto-hides
        # after a few seconds on its own.
        command = _build_command(
            ACTION_SHOW,
            message=TEST_MESSAGE,
            duration=TEST_DURATION,
            position="center",
            size=DEFAULT_SIZE,
            chime=True,
        )
        await _send(call.data[ATTR_ENTITY_ID], command)

    hass.services.async_register(DOMAIN, SERVICE_SHOW, handle_show, schema=SHOW_SCHEMA)
    hass.services.async_register(DOMAIN, SERVICE_HIDE, handle_hide, schema=HIDE_SCHEMA)
    hass.services.async_register(DOMAIN, SERVICE_TEST, handle_test, schema=TEST_SCHEMA)
    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    hass.services.async_remove(DOMAIN, SERVICE_SHOW)
    hass.services.async_remove(DOMAIN, SERVICE_HIDE)
    hass.services.async_remove(DOMAIN, SERVICE_TEST)
    return True
