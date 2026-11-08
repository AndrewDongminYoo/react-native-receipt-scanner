#ifndef RECEIPT_CORE_H
#define RECEIPT_CORE_H

#include <stdint.h>

#if defined(_WIN32)
#define RECEIPT_CORE_CALL __cdecl
#if defined(RECEIPT_CORE_SHARED)
#if defined(RECEIPT_CORE_BUILD)
#define RECEIPT_CORE_API __declspec(dllexport)
#else
#define RECEIPT_CORE_API __declspec(dllimport)
#endif
#else
#define RECEIPT_CORE_API
#endif
#else
#define RECEIPT_CORE_CALL
#define RECEIPT_CORE_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
#define RECEIPT_CORE_NOEXCEPT noexcept
extern "C" {
#else
#define RECEIPT_CORE_NOEXCEPT
#endif

#define RECEIPT_CORE_ABI_VERSION 1u
#define RECEIPT_CORE_OK 0u
#define RECEIPT_CORE_INVALID_INPUT 1u

/* ABI v1: two uint32_t fields, offsets 0/4, size 8, alignment 4.
 * Offsets and lengths address native-endian UTF-16 code units, not bytes.
 * Empty, unordered, overlapping and repeated ranges are allowed. Code units
 * are compared literally, including unpaired surrogates; no normalization or
 * UTF-16 validation is performed here.
 */
typedef struct receipt_core_line_v1 {
  uint32_t offset;
  uint32_t length;
} receipt_core_line_v1;

RECEIPT_CORE_API uint32_t RECEIPT_CORE_CALL
receipt_core_abi_version(void) RECEIPT_CORE_NOEXCEPT;

/* Synchronous, allocation-free, reentrant; retains no pointers. C linkage and
 * the platform C calling convention (__cdecl on Windows). Check the version
 * query against RECEIPT_CORE_ABI_VERSION before calling this v1 entry point.
 *
 * Each data/descriptor pointer may be null iff its corresponding length/count
 * is zero. Non-null zero-length storage is allowed. Every descriptor on BOTH
 * pages is validated, even if one page has no lines. offset <= buffer length
 * and length <= buffer length - offset are required. Declared byte sizes must
 * fit SIZE_MAX and PTRDIFF_MAX. Empty pages succeed with overlap zero.
 *
 * out_overlap is required, writable, aligned and must not alias any input.
 * It is zeroed on every invalid-input return when non-null. Callers provide
 * accessible, properly aligned storage of the declared size and keep inputs
 * immutable until return. Inaccessible pointers cannot be validated by C.
 *
 * Returns OK with the deepest exact suffix/prefix overlap containing >=2
 * distinct lines and >=12 UTF-16 units (including one unit per separator).
 * Zero means no proven overlap. Other returns are INVALID_INPUT. No C++
 * exception can cross this boundary. Host code owns filtering, normalization,
 * original text, assembly, quality policy and diagnostics.
 */
RECEIPT_CORE_API uint32_t RECEIPT_CORE_CALL receipt_core_find_overlap_v1(
    const uint16_t *left, uint32_t left_length,
    const receipt_core_line_v1 *left_lines, uint32_t left_count,
    const uint16_t *right, uint32_t right_length,
    const receipt_core_line_v1 *right_lines, uint32_t right_count,
    uint32_t *out_overlap) RECEIPT_CORE_NOEXCEPT;

#ifdef __cplusplus
}
#endif
#endif
