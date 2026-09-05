// Stub of the Pebble SDK 3.x/4.x public surface used by pebble/src/main.c,
// faithful to the real signatures. Used only for local
// `clang -fsyntax-only` validation — CloudPebble / the SDK toolchain build
// against the real header.
#ifndef STUB_PEBBLE_H
#define STUB_PEBBLE_H

#include <stdint.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>

typedef int Status;

#define APP_LOG(level, fmt, ...) ((void)0)
typedef enum {
  APP_LOG_LEVEL_ERROR, APP_LOG_LEVEL_WARNING, APP_LOG_LEVEL_INFO,
  APP_LOG_LEVEL_DEBUG, APP_LOG_LEVEL_DEBUG_VERBOSE
} AppLogLevel;

typedef enum {
  APP_MSG_OK, APP_MSG_SEND_TIMEOUT, APP_MSG_SEND_REJECTED, APP_MSG_NOT_CONNECTED,
  APP_MSG_APP_NOT_RUNNING, APP_MSG_BUSY, APP_MSG_TIMEOUT, APP_MSG_DISCONNECTED,
  APP_MSG_CLOSED, APP_MSG_INTERNAL_ERROR, APP_MSG_INVALID_STATE, APP_MSG_OUT_OF_MEMORY
} AppMessageResult;

// --- AppMessage / dictionary -------------------------------------------------

typedef enum {
  TUPLE_BYTES = 0, TUPLE_CSTRING = 1, TUPLE_UINT = 2, TUPLE_INT = 3
} TupleType;

typedef struct DictionaryIterator DictionaryIterator;

typedef struct {
  uint32_t key;
  uint8_t type;
  uint16_t length;
  union TupleValue *value; // pointer, per real SDK: tuple->value->cstring
  struct Tuple *next;
} Tuple;

typedef union TupleValue {
  uint8_t uint8;
  uint16_t uint16;
  uint32_t uint32;
  int8_t int8;
  int16_t int16;
  int32_t int32;
  uint8_t *bytes;
  char *cstring;
} TupleValue;

void *dict_write_cstring(DictionaryIterator *iterator, const uint32_t key, const char *cstring);
void *dict_write_uint8(DictionaryIterator *iterator, const uint32_t key, const uint8_t value);
void *dict_write_uint32(DictionaryIterator *iterator, const uint32_t key, const uint32_t value);
Tuple *dict_find(const DictionaryIterator *iter, const uint32_t key);
Tuple *dict_read_first(DictionaryIterator *iter);
Tuple *dict_read_next(DictionaryIterator *iter);

typedef void (*AppMessageInboxReceived)(DictionaryIterator *iter, void *context);
typedef void (*AppMessageInboxDropped)(AppMessageResult reason, void *context);
typedef void (*AppMessageOutboxSent)(DictionaryIterator *iter, void *context);
typedef void (*AppMessageOutboxFailed)(DictionaryIterator *iter, AppMessageResult reason, void *context);

void app_message_open(const uint32_t inbox_size, const uint32_t outbox_size);
void *app_message_set_context(void *context);
AppMessageInboxReceived app_message_register_inbox_received(AppMessageInboxReceived callback);
AppMessageInboxDropped app_message_register_inbox_dropped(AppMessageInboxDropped callback);
AppMessageOutboxSent app_message_register_outbox_sent(AppMessageOutboxSent callback);
AppMessageOutboxFailed app_message_register_outbox_failed(AppMessageOutboxFailed callback);
AppMessageResult app_message_outbox_begin(DictionaryIterator **iterator);
AppMessageResult app_message_outbox_send(void);
uint32_t app_message_inbox_size_maximum(void);
uint32_t app_message_outbox_size_maximum(void);

// --- persistence -------------------------------------------------------------

Status persist_write_int(const uint32_t key, const int32_t value);
int32_t persist_read_int(const uint32_t key);
Status persist_write_data(const uint32_t key, const void *buffer, const size_t size);
Status persist_read_data(const uint32_t key, void *buffer, const size_t size);
int persist_get_size(const uint32_t key);
bool persist_exists(const uint32_t key);
void persist_delete(const uint32_t key);

// --- graphics ---------------------------------------------------------------

typedef struct { int16_t x, y; } GPoint;
typedef struct { int16_t w, h; } GSize;
typedef struct { GPoint origin; GSize size; } GRect;
typedef uint8_t GColor;

typedef enum { GTextAlignmentLeft, GTextAlignmentCenter, GTextAlignmentRight } GTextAlignment;

// Matches the real SDK's function-like macro (positional init avoids the
// designator/macro-parameter collision).
#define GRect(x, y, w, h) ((GRect){{(x), (y)}, {(w), (h)}})

typedef struct GIcon GIcon;
typedef struct Layer Layer;
typedef struct GContext GContext;
typedef struct TextLayer TextLayer;
typedef struct Window Window;
typedef struct MenuLayer MenuLayer;

#define GColorBlack ((GColor)0b11000000)
#define GColorWhite ((GColor)0b11111111)
#define GColorLightGray ((GColor)0b11010101)
#define GColorIslamicGreen ((GColor)0b11001110)

void layer_add_child(Layer *parent, Layer *child);
GRect layer_get_bounds(const Layer *layer);

typedef void *GFont;
#define FONT_KEY_GOTHIC_14 "gothic_14"
#define FONT_KEY_GOTHIC_18 "gothic_18"
#define FONT_KEY_GOTHIC_24_BOLD "gothic_24_bold"
GFont fonts_get_system_font(const char *key);

TextLayer *text_layer_create(GRect frame);
void text_layer_destroy(TextLayer *text_layer);
Layer *text_layer_get_layer(const TextLayer *text_layer);
void text_layer_set_text(TextLayer *text_layer, const char *text);
void text_layer_set_font(TextLayer *text_layer, const GFont font);
void text_layer_set_text_alignment(TextLayer *text_layer, const GTextAlignment alignment);
void text_layer_set_text_color(TextLayer *text_layer, const GColor color);
void text_layer_set_background_color(TextLayer *text_layer, const GColor color);

typedef struct {
  void (*load)(Window *window);
  void (*unload)(Window *window);
  void (*appear)(Window *window);
  void (*disappear)(Window *window);
} WindowHandlers;

Window *window_create(void);
void window_destroy(Window *window);
void window_stack_push(Window *window, bool animated);
void window_stack_pop(bool animated);
void window_set_window_handlers(Window *window, WindowHandlers handlers);
Layer *window_get_root_layer(const Window *window);
void window_set_background_color(Window *window, const GColor color);

void vibes_short_pulse(void);
void vibes_long_pulse(void);
void vibes_double_pulse(void);

typedef struct AppTimer AppTimer;
AppTimer *app_timer_register(uint32_t timeout_ms, void (*callback)(void *), void *data);
void app_timer_cancel(AppTimer *timer);

void app_event_loop(void);

// --- menu -------------------------------------------------------------------

typedef uint16_t MenuIndexSection;
typedef uint16_t MenuIndexRow;
typedef struct { MenuIndexSection section; MenuIndexRow row; } MenuIndex;

typedef struct {
  // Render state of a menu cell
  bool is_selected;
  GRect clip;
  GColor background_color;
  GColor content_color;
} MenuLayerCellRenderProps;

typedef struct MenuLayerCallbacks {
  uint16_t (*get_num_rows)(struct MenuLayer *menu_layer, uint16_t section_index, void *callback_context);
  int16_t (*get_cell_height)(struct MenuLayer *menu_layer, MenuIndex *cell_index, void *callback_context);
  int16_t (*get_separator_height)(struct MenuLayer *menu_layer, MenuIndex *cell_index, void *callback_context);
  void (*draw_row)(GContext *ctx, const Layer *cell_layer, MenuIndex *cell_index, void *callback_context);
  void (*draw_background)(GContext *ctx, const Layer *back_layer, bool highlighted, void *callback_context);
  int16_t (*get_header_height)(struct MenuLayer *menu_layer, uint16_t section_index, void *callback_context);
  void (*draw_header)(GContext *ctx, const Layer *cell_layer, uint16_t section_index, void *callback_context);
  void (*select_click)(struct MenuLayer *menu_layer, MenuIndex *cell_index, void *callback_context);
  void (*select_long_click)(struct MenuLayer *menu_layer, MenuIndex *cell_index, void *callback_context);
} MenuLayerCallbacks;

MenuLayer *menu_layer_create(GRect frame);
void menu_layer_destroy(MenuLayer *menu_layer);
Layer *menu_layer_get_layer(const MenuLayer *menu_layer);
void menu_layer_set_callbacks(MenuLayer *menu_layer, void *callback_context, MenuLayerCallbacks callbacks);
void menu_layer_set_click_config_onto_window(MenuLayer *menu_layer, struct Window *window);
void menu_layer_reload_data(MenuLayer *menu_layer);
void menu_layer_set_normal_colors(MenuLayer *menu_layer, GColor background, GColor foreground);
void menu_layer_set_highlight_colors(MenuLayer *menu_layer, GColor background, GColor foreground);
void menu_cell_basic_draw(GContext *ctx, const Layer *cell_layer, const char *title, const char *subtitle, GIcon *icon);

#endif // STUB_PEBBLE_H