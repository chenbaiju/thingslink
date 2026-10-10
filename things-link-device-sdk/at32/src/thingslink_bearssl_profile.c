#include "thingslink_bearssl_profile.h"

void tl_bearssl_profile(br_ssl_client_context *client,
                       br_x509_minimal_context *certificates,
                       const br_x509_trust_anchor *anchors, size_t count)
{
    static const uint16_t suites[] =
        {BR_TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256};
    br_ssl_client_zero(client);
    br_ssl_engine_set_versions(&client->eng, BR_TLS12, BR_TLS12);
    br_ssl_engine_set_suites(&client->eng, suites, 1);
    br_ssl_engine_set_hash(&client->eng, br_sha256_ID, &br_sha256_vtable);
    br_ssl_engine_set_prf_sha256(&client->eng, br_tls12_sha256_prf);
    br_ssl_engine_set_ec(&client->eng, &br_ec_p256_m31);
    br_ssl_engine_set_ecdsa(&client->eng, br_ecdsa_i31_vrfy_asn1);
    br_ssl_engine_set_aes_ctr(&client->eng, &br_aes_ct_ctr_vtable);
    br_ssl_engine_set_ghash(&client->eng, br_ghash_ctmul32);
    br_ssl_engine_set_gcm(&client->eng, &br_sslrec_in_gcm_vtable,
                         &br_sslrec_out_gcm_vtable);
    br_x509_minimal_init(certificates, &br_sha256_vtable, anchors, count);
    br_x509_minimal_set_hash(certificates, br_sha256_ID, &br_sha256_vtable);
    br_x509_minimal_set_ecdsa(certificates, &br_ec_p256_m31,
                             br_ecdsa_i31_vrfy_asn1);
    br_ssl_engine_set_x509(&client->eng, &certificates->vtable);
}
