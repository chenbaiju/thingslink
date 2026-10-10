#ifndef THINGSLINK_BEARSSL_PROFILE_H
#define THINGSLINK_BEARSSL_PROFILE_H

#include "bearssl.h"

/* 仅启用TLS1.2、P-256/ECDSA/SHA256和AES128-GCM；不跳过X509校验。 */
void tl_bearssl_profile(br_ssl_client_context *, br_x509_minimal_context *,
                       const br_x509_trust_anchor *, size_t);

#endif
