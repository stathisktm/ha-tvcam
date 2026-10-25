"""Constants for the TV Camera Overlay integration."""

DOMAIN = "tvcam"

# Must match camera-overlay/src/main/kotlin/.../overlay/CameraOverlayService.kt's applicationId
# and action/extra names exactly.
PACKAGE_NAME = "com.pestpoint.radioauto.tvcam"
ACTION_SHOW = f"{PACKAGE_NAME}.action.SHOW_CAMERA"
ACTION_HIDE = f"{PACKAGE_NAME}.action.HIDE_CAMERA"

SERVICE_SHOW = "show"
SERVICE_HIDE = "hide"
SERVICE_TEST = "test"

ATTR_CAMERA_ENTITY = "camera_entity"
ATTR_VIDEO_URL = "video_url"
ATTR_IMAGE_URL = "image_url"
ATTR_TITLE = "title"
ATTR_MESSAGE = "message"
ATTR_DURATION = "duration"
ATTR_POSITION = "position"
ATTR_SIZE = "size"
ATTR_SOUND_URL = "sound_url"
ATTR_CHIME = "chime"
ATTR_LOADING_OPACITY = "loading_opacity"
ATTR_TEXT_POSITION = "text_position"
ATTR_TEXT_BACKGROUND_OPACITY = "text_background_opacity"
ATTR_ROUNDED_CORNERS = "rounded_corners"
ATTR_OK_WEBHOOK_ID = "ok_webhook_id"
ATTR_OK_RESPONSE_MESSAGE = "ok_response_message"
ATTR_OK_RESPONSE_IMAGE_URL = "ok_response_image_url"
ATTR_OK_RESPONSE_IMAGE_PATH = "ok_response_image_path"
ATTR_MUTED = "muted"
ATTR_SLOT = "slot"
ATTR_IMAGE_PATH = "image_path"
ATTR_MARGIN = "margin"
ATTR_TEXT_LAYOUT = "text_layout"
ATTR_ICON_URL = "icon_url"
ATTR_ICON_SIZE = "icon_size"

POSITIONS = ["top_left", "top_right", "bottom_left", "bottom_right", "center"]
DEFAULT_POSITION = "bottom_right"
DEFAULT_DURATION = 15
DEFAULT_MARGIN = 16

SIZES = ["small", "medium", "large"]
DEFAULT_SIZE = "medium"

DEFAULT_LOADING_OPACITY = 25

TEXT_POSITIONS = ["overlay", "below"]
DEFAULT_TEXT_POSITION = "overlay"

TEXT_LAYOUTS = ["vertical", "horizontal"]
DEFAULT_TEXT_LAYOUT = "vertical"
DEFAULT_TEXT_BACKGROUND_OPACITY = 70
DEFAULT_ROUNDED_CORNERS = True
DEFAULT_ICON_SIZE = 32

TEST_MESSAGE = "✓ TV Camera Overlay OK"
TEST_DURATION = 5
