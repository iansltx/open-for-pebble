#include <pebble.h>
#include <stdio.h>
#include <string.h>

// ---------------------------------------------------------------------------
// Alta Doors — Pebble Time 2 watch app (platform: emery, SDK 3+/4)
//
// Shows the curated door list synced from the Android companion app
// ("Alta Pebble Bridge"). Selecting a door asks the companion to trigger an
// unlock through the Avigilon Alta Open app (see companion/ for details).
//
// AppMessage keys MUST match companion/app/src/main/java/.../pebble/PebbleBridge.kt
// ---------------------------------------------------------------------------

#define MAX_DOORS 12
#define NAME_LEN 32

// Item types (must match DoorType in the companion)
#define DOOR_TYPE_ENTRY 0
#define DOOR_TYPE_READER 1

// AppMessage keys — keep in sync with PebbleBridge.kt
enum {
  KEY_HELLO = 0,          // watch -> phone: cstring, protocol version
  KEY_DOORS_COUNT = 1,    // phone -> watch: uint32, number of doors to expect
  KEY_DOORS_END = 2,      // phone -> watch: uint8 == 1, list finished
  KEY_DOOR_ID = 3,        // phone -> watch: uint32
  KEY_DOOR_NAME = 4,      // phone -> watch: cstring
  KEY_DOOR_TYPE = 5,      // phone -> watch: uint8
  KEY_UNLOCK_ID = 10,     // watch -> phone: uint32
  KEY_UNLOCK_TYPE = 11,   // watch -> phone: uint8
  KEY_UNLOCK_REQID = 12,  // watch -> phone: uint32
  KEY_RESULT_REQID = 15,  // phone -> watch: uint32
  KEY_RESULT_STATUS = 16, // phone -> watch: uint8
  KEY_RESULT_TEXT = 17    // phone -> watch: cstring
};

// Unlock result statuses (must match TriggerStatus in the companion)
#define RESULT_OK 0
#define RESULT_ERROR 1
#define RESULT_BLOCKED 2

typedef struct {
  uint32_t id;
  uint8_t type;
  char name[NAME_LEN + 1];
} Door;

static Door s_doors[MAX_DOORS];
static int s_door_count = 0;

static Window *s_window;
static MenuLayer *s_menu;

// Status window (pushed while an unlock request is in flight)
static Window *s_status_window;
static TextLayer *s_status_title;
static TextLayer *s_status_body;
static char s_status_title_buf[NAME_LEN + 1];
static char s_status_body_buf[80];

static uint32_t s_next_request_id = 1;
static uint32_t s_pending_request_id = 0;
static AppTimer *s_result_timer = NULL;
static AppTimer *s_hello_timer = NULL;
static int s_hello_attempts = 0;

// Persist keys
#define PERSIST_KEY_COUNT 100
#define PERSIST_KEY_DOOR_BASE 200

static void save_doors(void) {
  persist_write_int(PERSIST_KEY_COUNT, s_door_count);
  for (int i = 0; i < s_door_count && i < MAX_DOORS; i++) {
    persist_write_data(PERSIST_KEY_DOOR_BASE + i, &s_doors[i], sizeof(Door));
  }
}

static void load_doors(void) {
  s_door_count = 0;
  if (!persist_exists(PERSIST_KEY_COUNT)) return;
  int count = (int)persist_read_int(PERSIST_KEY_COUNT);
  if (count < 0 || count > MAX_DOORS) return;
  for (int i = 0; i < count; i++) {
    Door door;
    if (persist_exists(PERSIST_KEY_DOOR_BASE + i) &&
        persist_read_data(PERSIST_KEY_DOOR_BASE + i, &door, sizeof(Door)) == (int)sizeof(Door) &&
        door.name[0] != '\0') {
      memcpy(&s_doors[s_door_count++], &door, sizeof(Door));
    }
  }
}

// --------------------------------------------------------------------------
// Menu
// --------------------------------------------------------------------------

#define STATUS_ROW_HEIGHT 56
#define DOOR_ROW_HEIGHT 46
#define SYNC_ROW_INDEX() (s_door_count > 0 ? s_door_count : 1)

static uint16_t menu_get_num_rows(MenuLayer *menu, uint16_t section, void *context) {
  // [doors...] or one placeholder + a "sync" row
  return (uint16_t)(SYNC_ROW_INDEX() + 1);
}

static int16_t menu_get_cell_height(MenuLayer *menu, MenuIndex *index, void *context) {
  if (s_door_count == 0 && index->row == 0) return STATUS_ROW_HEIGHT;
  return DOOR_ROW_HEIGHT;
}

static void menu_draw_row(GContext *ctx, const Layer *cell_layer, MenuIndex *index, void *context) {
  if (index->row == (uint16_t)SYNC_ROW_INDEX()) {
    menu_cell_basic_draw(ctx, cell_layer, "Sync doors", NULL, NULL);
    return;
  }
  if (s_door_count == 0 && index->row == 0) {
    menu_cell_basic_draw(ctx, cell_layer, "Waiting for doors…",
                         "Open the bridge app on your phone", NULL);
    return;
  }
  Door *door = &s_doors[index->row];
  char subtitle[32];
  if (door->type == DOOR_TYPE_ENTRY) {
    snprintf(subtitle, sizeof(subtitle), "Entry #%lu", (unsigned long)door->id);
  } else {
    snprintf(subtitle, sizeof(subtitle), "Reader #%lu", (unsigned long)door->id);
  }
  menu_cell_basic_draw(ctx, cell_layer, door->name, subtitle, NULL);
}

static void send_hello(void);
static void hello_retry_cb(void *data);
static void result_timeout_cb(void *data);
static void pop_status_cb(void *data);

static void menu_select(MenuLayer *menu, MenuIndex *index, void *context) {
  if (index->row == (uint16_t)SYNC_ROW_INDEX()) {
    // Manual resync
    s_hello_attempts = 0;
    send_hello();
    vibes_short_pulse();
    return;
  }
  if (s_door_count == 0) {
    vibes_short_pulse();
    return;
  }
  Door *door = &s_doors[index->row];

  DictionaryIterator *iter;
  if (app_message_outbox_begin(&iter) != APP_MSG_OK) {
    return;
  }
  uint32_t request_id = s_next_request_id++;
  dict_write_uint32(iter, KEY_UNLOCK_ID, door->id);
  dict_write_uint8(iter, KEY_UNLOCK_TYPE, door->type);
  dict_write_uint32(iter, KEY_UNLOCK_REQID, request_id);
  if (app_message_outbox_send() != APP_MSG_OK) {
    return;
  }
  s_pending_request_id = request_id;

  // Show status window
  if (s_result_timer) {
    app_timer_cancel(s_result_timer);
    s_result_timer = NULL;
  }
  strncpy(s_status_title_buf, door->name, NAME_LEN);
  s_status_title_buf[NAME_LEN] = '\0';
  strncpy(s_status_body_buf, "Requesting…", sizeof(s_status_body_buf));
  text_layer_set_text(s_status_title, s_status_title_buf);
  text_layer_set_text(s_status_body, s_status_body_buf);
  vibes_short_pulse();
  window_stack_push(s_status_window, true);

  // Timeout: no response from the phone
  s_result_timer = app_timer_register(8000, result_timeout_cb, NULL);
}

static void pop_status_cb(void *data) {
  (void)data;
  window_stack_pop(true);
}

static void result_timeout_cb(void *data) {
  (void)data;
  if (s_pending_request_id != 0) {
    s_pending_request_id = 0;
    strncpy(s_status_body_buf, "No response.\nIs the bridge app running?", sizeof(s_status_body_buf));
    text_layer_set_text(s_status_body, s_status_body_buf);
    vibes_double_pulse();
    app_timer_register(2500, pop_status_cb, NULL);
  }
}

// --------------------------------------------------------------------------
// Status window
// --------------------------------------------------------------------------

static void status_window_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  GRect bounds = layer_get_bounds(root);

  s_status_title = text_layer_create(GRect(8, 40, bounds.size.w - 16, 30));
  text_layer_set_font(s_status_title, fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD));
  text_layer_set_text_alignment(s_status_title, GTextAlignmentCenter);
  text_layer_set_text(s_status_title, "");

  s_status_body = text_layer_create(GRect(8, 80, bounds.size.w - 16, 90));
  text_layer_set_font(s_status_body, fonts_get_system_font(FONT_KEY_GOTHIC_18));
  text_layer_set_text_alignment(s_status_body, GTextAlignmentCenter);
  text_layer_set_text(s_status_body, "");

#ifdef PBL_COLOR
  text_layer_set_text_color(s_status_title, GColorWhite);
  text_layer_set_text_color(s_status_body, GColorLightGray);
#endif

  layer_add_child(root, text_layer_get_layer(s_status_title));
  layer_add_child(root, text_layer_get_layer(s_status_body));
}

static void status_window_unload(Window *window) {
  text_layer_destroy(s_status_title);
  text_layer_destroy(s_status_body);
  s_status_title = NULL;
  s_status_body = NULL;
}

// --------------------------------------------------------------------------
// AppMessage
// --------------------------------------------------------------------------

static void handle_result(uint8_t status, const char *text) {
  if (s_result_timer) {
    app_timer_cancel(s_result_timer);
    s_result_timer = NULL;
  }
  bool had_pending = (s_pending_request_id != 0);
  s_pending_request_id = 0;

  const char *msg;
  if (text && text[0]) {
    msg = text;
  } else if (status == RESULT_OK) {
    msg = "Unlock requested\nin Alta Open ✓";
  } else if (status == RESULT_BLOCKED) {
    msg = "Phone blocked the request.\nTap the notification to confirm.";
  } else {
    msg = "Failed. Check the\nbridge app.";
  }
  strncpy(s_status_body_buf, msg, sizeof(s_status_body_buf) - 1);
  s_status_body_buf[sizeof(s_status_body_buf) - 1] = '\0';

  if (had_pending) {
    if (status == RESULT_OK) {
      vibes_long_pulse();
    } else {
      vibes_double_pulse();
    }
    if (s_status_body && s_status_title) {
      text_layer_set_text(s_status_body, s_status_body_buf);
      app_timer_register(2200, pop_status_cb, NULL);
    }
  }
}

static void inbox_received(DictionaryIterator *iter, void *context) {
  Tuple *t_count = dict_find(iter, KEY_DOORS_COUNT);
  Tuple *t_end = dict_find(iter, KEY_DOORS_END);
  Tuple *t_door_id = dict_find(iter, KEY_DOOR_ID);
  Tuple *t_door_name = dict_find(iter, KEY_DOOR_NAME);
  Tuple *t_door_type = dict_find(iter, KEY_DOOR_TYPE);
  Tuple *t_reqid = dict_find(iter, KEY_RESULT_REQID);
  Tuple *t_status = dict_find(iter, KEY_RESULT_STATUS);
  Tuple *t_text = dict_find(iter, KEY_RESULT_TEXT);

  if (t_count) {
    // Start of a fresh list
    s_door_count = 0;
    if (s_hello_timer) {
      app_timer_cancel(s_hello_timer);
      s_hello_timer = NULL;
    }
    menu_layer_reload_data(s_menu);
  }
  if (t_door_id && t_door_name && t_door_type && s_door_count < MAX_DOORS) {
    Door *d = &s_doors[s_door_count];
    d->id = t_door_id->value->uint32;
    d->type = t_door_type->value->uint8 == DOOR_TYPE_READER ? DOOR_TYPE_READER : DOOR_TYPE_ENTRY;
    const char *name = t_door_name->value->cstring;
    strncpy(d->name, name, NAME_LEN);
    d->name[NAME_LEN] = '\0';
    s_door_count++;
  }
  if (t_end) {
    save_doors();
    menu_layer_reload_data(s_menu);
  }
  if (t_reqid && t_status) {
    uint8_t status = t_status->value->uint8;
    handle_result(status, t_text ? t_text->value->cstring : NULL);
  }
}

static void inbox_dropped(AppMessageResult reason, void *context) {
  APP_LOG(APP_LOG_LEVEL_WARNING, "inbox dropped: %d", (int)reason);
}

static void outbox_sent(DictionaryIterator *iter, void *context) {
  if (dict_find(iter, KEY_HELLO)) {
    // Phone accepted our hello; door list should arrive shortly.
    APP_LOG(APP_LOG_LEVEL_INFO, "hello acked");
  }
}

static void outbox_failed(DictionaryIterator *iter, AppMessageResult reason, void *context) {
  if (dict_find(iter, KEY_HELLO)) {
    // No companion receiver on the phone
    if (s_status_title) { /* nothing to do; menu already shows hint */ }
    APP_LOG(APP_LOG_LEVEL_WARNING, "hello failed: %d — bridge app not installed?", (int)reason);
  }
}

static void hello_retry_cb(void *data) {
  (void)data;
  send_hello();
}

static void send_hello(void) {
  DictionaryIterator *iter;
  if (app_message_outbox_begin(&iter) == APP_MSG_OK) {
    dict_write_cstring(iter, KEY_HELLO, "1");
    if (app_message_outbox_send() == APP_MSG_OK) {
      s_hello_attempts++;
      if (s_hello_timer) app_timer_cancel(s_hello_timer);
      // Retry a few times in case the phone was busy
      if (s_hello_attempts < 5) {
        s_hello_timer = app_timer_register(4000, hello_retry_cb, NULL);
      }
    }
  }
}

// --------------------------------------------------------------------------
// Main window
// --------------------------------------------------------------------------

static void main_window_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  GRect bounds = layer_get_bounds(root);

  s_menu = menu_layer_create(bounds);
  menu_layer_set_callbacks(s_menu, NULL, (MenuLayerCallbacks) {
    .get_num_rows = menu_get_num_rows,
    .get_cell_height = menu_get_cell_height,
    .draw_row = menu_draw_row,
    .select_click = menu_select,
  });
  menu_layer_set_click_config_onto_window(s_menu, window);
#ifdef PBL_COLOR
  menu_layer_set_normal_colors(s_menu, GColorBlack, GColorWhite);
  menu_layer_set_highlight_colors(s_menu, GColorIslamicGreen, GColorWhite);
#endif
  layer_add_child(root, menu_layer_get_layer(s_menu));
}

static void main_window_unload(Window *window) {
  menu_layer_destroy(s_menu);
}

static void init(void) {
  load_doors();

  s_window = window_create();
  window_set_window_handlers(s_window, (WindowHandlers) {
    .load = main_window_load,
    .unload = main_window_unload,
  });
  window_stack_push(s_window, true);

  s_status_window = window_create();
  window_set_window_handlers(s_status_window, (WindowHandlers) {
    .load = status_window_load,
    .unload = status_window_unload,
  });

  app_message_register_inbox_received(inbox_received);
  app_message_register_inbox_dropped(inbox_dropped);
  app_message_register_outbox_sent(outbox_sent);
  app_message_register_outbox_failed(outbox_failed);
  app_message_open(256, 64);

  send_hello();
}

static void deinit(void) {
  if (s_result_timer) app_timer_cancel(s_result_timer);
  if (s_hello_timer) app_timer_cancel(s_hello_timer);
  window_destroy(s_status_window);
  window_destroy(s_window);
}

int main(void) {
  init();
  app_event_loop();
  deinit();
}