/*
 * Bonjour backend for UxPlay on Android (replaces lib/dns_sd/dns_sd.c).
 *
 * Android has no dns_sd API in the NDK. This backend only builds the TXT records,
 * exactly as dns_sd.c does, and keeps them in DNS wire format (length-prefixed
 * "key=value" strings). The Java side reads them with zenairplay_txt() and registers
 * the services through NsdManager. UxPlay itself also reads them for GET /info.
 *
 * Licence: LGPL-2.1+ (derived from UxPlay lib/dns_sd/dns_sd.c, Juho Vaha-Herttua, fduncanh).
 */

#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#include "dnssd.h"
#include "dnssdint.h"
#include "utils.h"

#define MAX_TXT 1024

typedef struct {
    unsigned char buf[MAX_TXT];
    int len;
} txt_t;

typedef struct {
    txt_t raop, airplay;
    int raop_port, airplay_port;
    char raop_name[256];
} dnssd_android_t;

static void txt_set(txt_t *t, const char *key, const char *value) {
    int kl = (int) strlen(key), vl = (int) strlen(value);
    int n = kl + 1 + vl;
    if (n > 255 || t->len + 1 + n > MAX_TXT) return;
    t->buf[t->len++] = (unsigned char) n;
    memcpy(t->buf + t->len, key, kl); t->len += kl;
    t->buf[t->len++] = '=';
    memcpy(t->buf + t->len, value, vl); t->len += vl;
}

void *dnssd_private_init(dnssd_t *dnssd_public, int *error) {
    (void) dnssd_public;
    dnssd_android_t *d = calloc(1, sizeof(dnssd_android_t));
    if (!d && error) *error = DNSSD_ERROR_OUTOFMEM;
    return d;
}

void dnssd_private_destroy(void *p) { free(p); }

void dnssd_error_text(int *error, const char *appname) {
    (void) appname;
    if (error) fprintf(stderr, "dnssd error %d\n", *error);
}

int dnssd_register_raop(dnssd_t *pub, unsigned short port) {
    dnssd_android_t *d = pub->dnssd_private;
    char features[22];
    snprintf(features, sizeof(features), "0x%X,0x%X", pub->features1, pub->features2);
    txt_t *t = &d->raop;
    t->len = 0;
    txt_set(t, "ch", RAOP_CH);
    txt_set(t, "cn", RAOP_CN);
    txt_set(t, "da", RAOP_DA);
    txt_set(t, "et", RAOP_ET);
    txt_set(t, "vv", RAOP_VV);
    txt_set(t, "ft", features);
    txt_set(t, "am", GLOBAL_MODEL);
    txt_set(t, "md", RAOP_MD);
    txt_set(t, "rhd", RAOP_RHD);
    switch (pub->pin_pw) {
    case 1: txt_set(t, "pw", "true"); txt_set(t, "sf", "0x8c"); break;
    case 2: case 3: txt_set(t, "pw", "true"); txt_set(t, "sf", "0x84"); break;
    default: txt_set(t, "pw", "false"); txt_set(t, "sf", RAOP_SF); break;
    }
    txt_set(t, "sr", RAOP_SR);
    txt_set(t, "ss", RAOP_SS);
    txt_set(t, "sv", RAOP_SV);
    txt_set(t, "tp", RAOP_TP);
    txt_set(t, "txtvers", RAOP_TXTVERS);
    txt_set(t, "vs", RAOP_VS);
    txt_set(t, "vn", RAOP_VN);
    txt_set(t, "pk", pub->pk ? pub->pk : "");
    if (utils_hwaddr_raop(d->raop_name, sizeof(d->raop_name), pub->hw_addr, pub->hw_addr_len) < 0) return -1;
    strncat(d->raop_name, "@", sizeof(d->raop_name) - strlen(d->raop_name) - 1);
    strncat(d->raop_name, pub->name, sizeof(d->raop_name) - strlen(d->raop_name) - 1);
    d->raop_port = port;
    return 0;
}

int dnssd_register_airplay(dnssd_t *pub, unsigned short port) {
    dnssd_android_t *d = pub->dnssd_private;
    char device_id[3 * MAX_HWADDR_LEN];
    char features[22];
    snprintf(features, sizeof(features), "0x%X,0x%X", pub->features1, pub->features2);
    if (utils_hwaddr_airplay(device_id, sizeof(device_id), pub->hw_addr, pub->hw_addr_len) < 0) return -1;
    txt_t *t = &d->airplay;
    t->len = 0;
    txt_set(t, "deviceid", device_id);
    txt_set(t, "features", features);
    txt_set(t, "pw", pub->pin_pw ? "true" : "false");
    txt_set(t, "flags", "0x4");
    txt_set(t, "model", GLOBAL_MODEL);
    txt_set(t, "pk", pub->pk ? pub->pk : "");
    txt_set(t, "pi", AIRPLAY_PI);
    txt_set(t, "srcvers", AIRPLAY_SRCVERS);
    txt_set(t, "vv", AIRPLAY_VV);
    d->airplay_port = port;
    return 0;
}

void dnssd_unregister_raop(dnssd_t *pub) { ((dnssd_android_t *) pub->dnssd_private)->raop_port = 0; }
void dnssd_unregister_airplay(dnssd_t *pub) { ((dnssd_android_t *) pub->dnssd_private)->airplay_port = 0; }

const char *dnssd_get_raop_txt(dnssd_t *pub, int *length) {
    dnssd_android_t *d = pub->dnssd_private;
    *length = d->raop.len;
    return (const char *) d->raop.buf;
}

const char *dnssd_get_airplay_txt(dnssd_t *pub, int *length) {
    dnssd_android_t *d = pub->dnssd_private;
    *length = d->airplay.len;
    return (const char *) d->airplay.buf;
}

int dnssd_get_service_fd(dnssd_t *dnssd, int service) { (void) dnssd; (void) service; return -1; }
int dnssd_process_service(dnssd_t *dnssd, int service) { (void) dnssd; (void) service; return 0; }

/* for the JNI glue: service name of the _raop._tcp record ("HWADDR@name") */
const char *dnssd_android_raop_name(dnssd_t *pub) {
    return ((dnssd_android_t *) pub->dnssd_private)->raop_name;
}
