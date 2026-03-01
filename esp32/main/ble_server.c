#include "ble_server.h"
#include "config.h"

#include <string.h>

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_log.h"
#include "esp_bt.h"
#include "esp_gap_ble_api.h"
#include "esp_gatts_api.h"
#include "esp_bt_main.h"
#include "esp_gatt_common_api.h"
#include "nvs_flash.h"

static const char *TAG = "ble_server";

/* ---- GATT attribute handle indices --------------------------------------- */
#define IDX_SVC               0
#define IDX_CHR_CSI           1
#define IDX_CHR_CSI_VAL       2
#define IDX_CHR_CSI_CFG       3   /* CCCD for notifications */
#define IDX_CHR_CFG           4
#define IDX_CHR_CFG_VAL       5
#define IDX_CHR_STS           6
#define IDX_CHR_STS_VAL       7
#define HANDLE_NUM            8

/* ---- UUID definitions ---------------------------------------------------- */
static const uint8_t SVC_UUID[16]     = BLE_SVC_UUID128;
static const uint8_t CSI_UUID[16]     = BLE_CHR_CSI_UUID128;
static const uint8_t CFG_UUID[16]     = BLE_CHR_CFG_UUID128;
static const uint8_t STS_UUID[16]     = BLE_CHR_STS_UUID128;

/* ---- Attribute values ---------------------------------------------------- */
static uint8_t  s_csi_val[CSI_PACKET_TOTAL_BYTES];
static uint16_t s_csi_cccd   = 0;           /* client characteristic config  */
static uint8_t  s_cfg_val[4] = {0};         /* 4-byte config placeholder     */
static uint8_t  s_sts_val[1] = {0};         /* 1-byte status (0=idle,1=run)  */

/* ---- Runtime state ------------------------------------------------------- */
static uint16_t s_gatts_if     = ESP_GATT_IF_NONE;
static uint16_t s_conn_id      = 0xFFFF;
static uint16_t s_handles[HANDLE_NUM];
static bool     s_connected    = false;
static bool     s_notif_enabled = false;
static esp_gatt_perm_t RD  = ESP_GATT_PERM_READ;
static esp_gatt_perm_t RW  = ESP_GATT_PERM_READ | ESP_GATT_PERM_WRITE;

/* ---- GATT database ------------------------------------------------------- */
static const esp_gatts_attr_db_t s_gatt_db[HANDLE_NUM] = {
    /* [0] Service declaration */
    [IDX_SVC] = {
        .att_desc = {
            .uuid_length = ESP_UUID_LEN_16,
            .uuid_p      = (uint8_t *)&ESP_GATT_UUID_PRI_SERVICE,
            .perm        = RD,
            .max_length  = sizeof(SVC_UUID),
            .length      = sizeof(SVC_UUID),
            .value       = (uint8_t *)SVC_UUID,
        }
    },

    /* [1] CSI characteristic declaration */
    [IDX_CHR_CSI] = {
        .att_desc = {
            .uuid_length = ESP_UUID_LEN_16,
            .uuid_p      = (uint8_t *)&ESP_GATT_UUID_CHAR_DECLARE,
            .perm        = RD,
            .max_length  = sizeof(uint8_t),
            .length      = sizeof(uint8_t),
            .value       = (uint8_t[]){ESP_GATT_CHAR_PROP_BIT_NOTIFY},
        }
    },

    /* [2] CSI characteristic value */
    [IDX_CHR_CSI_VAL] = {
        .att_desc = {
            .uuid_length = ESP_UUID_LEN_128,
            .uuid_p      = (uint8_t *)CSI_UUID,
            .perm        = RD,
            .max_length  = sizeof(s_csi_val),
            .length      = sizeof(s_csi_val),
            .value       = s_csi_val,
        }
    },

    /* [3] CSI CCCD */
    [IDX_CHR_CSI_CFG] = {
        .att_desc = {
            .uuid_length = ESP_UUID_LEN_16,
            .uuid_p      = (uint8_t *)&ESP_GATT_UUID_CHAR_CLIENT_CONFIG,
            .perm        = RW,
            .max_length  = sizeof(s_csi_cccd),
            .length      = sizeof(s_csi_cccd),
            .value       = (uint8_t *)&s_csi_cccd,
        }
    },

    /* [4] Config characteristic declaration */
    [IDX_CHR_CFG] = {
        .att_desc = {
            .uuid_length = ESP_UUID_LEN_16,
            .uuid_p      = (uint8_t *)&ESP_GATT_UUID_CHAR_DECLARE,
            .perm        = RD,
            .max_length  = sizeof(uint8_t),
            .length      = sizeof(uint8_t),
            .value       = (uint8_t[]){ESP_GATT_CHAR_PROP_BIT_READ |
                                       ESP_GATT_CHAR_PROP_BIT_WRITE},
        }
    },

    /* [5] Config characteristic value */
    [IDX_CHR_CFG_VAL] = {
        .att_desc = {
            .uuid_length = ESP_UUID_LEN_128,
            .uuid_p      = (uint8_t *)CFG_UUID,
            .perm        = RW,
            .max_length  = sizeof(s_cfg_val),
            .length      = sizeof(s_cfg_val),
            .value       = s_cfg_val,
        }
    },

    /* [6] Status characteristic declaration */
    [IDX_CHR_STS] = {
        .att_desc = {
            .uuid_length = ESP_UUID_LEN_16,
            .uuid_p      = (uint8_t *)&ESP_GATT_UUID_CHAR_DECLARE,
            .perm        = RD,
            .max_length  = sizeof(uint8_t),
            .length      = sizeof(uint8_t),
            .value       = (uint8_t[]){ESP_GATT_CHAR_PROP_BIT_READ},
        }
    },

    /* [7] Status characteristic value */
    [IDX_CHR_STS_VAL] = {
        .att_desc = {
            .uuid_length = ESP_UUID_LEN_128,
            .uuid_p      = (uint8_t *)STS_UUID,
            .perm        = RD,
            .max_length  = sizeof(s_sts_val),
            .length      = sizeof(s_sts_val),
            .value       = s_sts_val,
        }
    },
};

/* ---- GAP advertising ----------------------------------------------------- */

static esp_ble_adv_params_t s_adv_params = {
    .adv_int_min       = 0x20,   /* 20 ms */
    .adv_int_max       = 0x40,   /* 40 ms */
    .adv_type          = ADV_TYPE_IND,
    .own_addr_type     = BLE_ADDR_TYPE_PUBLIC,
    .channel_map       = ADV_CHNL_ALL,
    .adv_filter_policy = ADV_FILTER_ALLOW_SCAN_ANY_CON_ANY,
};

static void gap_event_handler(esp_gap_ble_cb_event_t event,
                              esp_ble_gap_cb_param_t *param)
{
    switch (event) {
    case ESP_GAP_BLE_ADV_DATA_SET_COMPLETE_EVT:
        esp_ble_gap_start_advertising(&s_adv_params);
        break;

    case ESP_GAP_BLE_ADV_START_COMPLETE_EVT:
        if (param->adv_start_cmpl.status != ESP_BT_STATUS_SUCCESS) {
            ESP_LOGE(TAG, "Advertising start failed: %d",
                     param->adv_start_cmpl.status);
        } else {
            ESP_LOGI(TAG, "BLE advertising started");
        }
        break;

    case ESP_GAP_BLE_ADV_STOP_COMPLETE_EVT:
        ESP_LOGI(TAG, "BLE advertising stopped");
        break;

    default:
        break;
    }
}

/* ---- GATTS event handler ------------------------------------------------- */

static void gatts_event_handler(esp_gatts_cb_event_t event,
                                esp_gatt_if_t gatts_if,
                                esp_ble_gatts_cb_param_t *param)
{
    switch (event) {

    case ESP_GATTS_REG_EVT:
        ESP_LOGI(TAG, "GATTS registered, app_id=%d", param->reg.app_id);
        s_gatts_if = gatts_if;

        /* Set device name */
        esp_ble_gap_set_device_name(BLE_DEVICE_NAME);

        /* Advertising data: include device name */
        esp_ble_adv_data_t adv_data = {
            .set_scan_rsp        = false,
            .include_name        = true,
            .include_txpower     = true,
            .min_interval        = 0x0006,
            .max_interval        = 0x0010,
            .appearance          = 0x00,
            .manufacturer_len    = 0,
            .p_manufacturer_data = NULL,
            .service_data_len    = 0,
            .p_service_data      = NULL,
            .service_uuid_len    = 0,
            .p_service_uuid      = NULL,
            .flag = (ESP_BLE_ADV_FLAG_GEN_DISC | ESP_BLE_ADV_FLAG_BREDR_NOT_SPT),
        };
        esp_ble_gap_config_adv_data(&adv_data);

        /* Create the GATT attribute table */
        esp_ble_gatts_create_attr_tab(s_gatt_db, gatts_if,
                                      HANDLE_NUM, 0);
        break;

    case ESP_GATTS_CREAT_ATTR_TAB_EVT:
        if (param->add_attr_tab.status != ESP_GATT_OK) {
            ESP_LOGE(TAG, "Attribute table creation failed: 0x%04x",
                     param->add_attr_tab.status);
            break;
        }
        if (param->add_attr_tab.num_handle != HANDLE_NUM) {
            ESP_LOGE(TAG, "Unexpected handle count: %d (expected %d)",
                     param->add_attr_tab.num_handle, HANDLE_NUM);
            break;
        }
        memcpy(s_handles, param->add_attr_tab.handles, sizeof(s_handles));
        esp_ble_gatts_start_service(s_handles[IDX_SVC]);
        ESP_LOGI(TAG, "GATT attribute table created, service started");
        break;

    case ESP_GATTS_CONNECT_EVT:
        s_connected = true;
        s_conn_id   = param->connect.conn_id;
        ESP_LOGI(TAG, "BLE client connected, conn_id=%d", s_conn_id);
        /* Request a larger MTU */
        esp_ble_gatt_set_local_mtu(BLE_MTU);
        break;

    case ESP_GATTS_DISCONNECT_EVT:
        s_connected     = false;
        s_notif_enabled = false;
        s_conn_id       = 0xFFFF;
        s_csi_cccd      = 0;
        ESP_LOGI(TAG, "BLE client disconnected, reason=0x%02x",
                 param->disconnect.reason);
        /* Restart advertising */
        esp_ble_gap_start_advertising(&s_adv_params);
        break;

    case ESP_GATTS_WRITE_EVT:
        if (!param->write.is_prep &&
            param->write.handle == s_handles[IDX_CHR_CSI_CFG] &&
            param->write.len == 2) {
            uint16_t cccd_val = (uint16_t)(param->write.value[0]) |
                                ((uint16_t)(param->write.value[1]) << 8);
            s_csi_cccd      = cccd_val;
            s_notif_enabled = (cccd_val == 0x0001);
            ESP_LOGI(TAG, "CSI CCCD written: 0x%04x (notify=%s)",
                     cccd_val, s_notif_enabled ? "enabled" : "disabled");
        } else if (!param->write.is_prep &&
                   param->write.handle == s_handles[IDX_CHR_CFG_VAL]) {
            uint16_t copy_len = param->write.len < sizeof(s_cfg_val)
                                ? param->write.len : sizeof(s_cfg_val);
            memcpy(s_cfg_val, param->write.value, copy_len);
            ESP_LOGI(TAG, "Config characteristic written (%d bytes)", copy_len);
        }
        if (param->write.need_rsp) {
            esp_ble_gatts_send_response(gatts_if, param->write.conn_id,
                                        param->write.trans_id,
                                        ESP_GATT_OK, NULL);
        }
        break;

    case ESP_GATTS_MTU_EVT:
        ESP_LOGI(TAG, "MTU exchanged: %d bytes", param->mtu.mtu);
        break;

    default:
        break;
    }
}

/* ---- Public API ---------------------------------------------------------- */

esp_err_t ble_server_init(void)
{
    esp_err_t err;

    esp_bt_controller_config_t bt_cfg = BT_CONTROLLER_INIT_CONFIG_DEFAULT();
    err = esp_bt_controller_init(&bt_cfg);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "esp_bt_controller_init failed: %s", esp_err_to_name(err));
        return err;
    }

    err = esp_bt_controller_enable(ESP_BT_MODE_BLE);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "esp_bt_controller_enable failed: %s", esp_err_to_name(err));
        return err;
    }

    err = esp_bluedroid_init();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "esp_bluedroid_init failed: %s", esp_err_to_name(err));
        return err;
    }

    err = esp_bluedroid_enable();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "esp_bluedroid_enable failed: %s", esp_err_to_name(err));
        return err;
    }

    err = esp_ble_gatts_register_callback(gatts_event_handler);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "esp_ble_gatts_register_callback failed: %s",
                 esp_err_to_name(err));
        return err;
    }

    err = esp_ble_gap_register_callback(gap_event_handler);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "esp_ble_gap_register_callback failed: %s",
                 esp_err_to_name(err));
        return err;
    }

    err = esp_ble_gatts_app_register(0);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "esp_ble_gatts_app_register failed: %s",
                 esp_err_to_name(err));
        return err;
    }

    ESP_LOGI(TAG, "BLE server initialised (device name: %s)", BLE_DEVICE_NAME);
    return ESP_OK;
}

esp_err_t ble_server_send_csi_notification(const uint8_t *data, uint16_t len)
{
    if (!s_connected || !s_notif_enabled) {
        return ESP_OK;   /* no subscriber – not an error */
    }

    if (data == NULL || len == 0) {
        return ESP_ERR_INVALID_ARG;
    }

    /* Update local attribute value cache */
    uint16_t copy_len = len < sizeof(s_csi_val) ? len : sizeof(s_csi_val);
    memcpy(s_csi_val, data, copy_len);
    s_sts_val[0] = 1;   /* mark as running */

    esp_err_t err = esp_ble_gatts_send_indicate(s_gatts_if,
                                                 s_conn_id,
                                                 s_handles[IDX_CHR_CSI_VAL],
                                                 copy_len,
                                                 (uint8_t *)data,
                                                 false /* notify, not indicate */);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "esp_ble_gatts_send_indicate failed: %s",
                 esp_err_to_name(err));
        return err;
    }

    return ESP_OK;
}

bool ble_server_is_connected(void)
{
    return s_connected;
}
