#include "receipt_core.h"
#include <stddef.h>

_Static_assert(sizeof(receipt_core_line_v1) == 8, "ABI size");
_Static_assert(_Alignof(receipt_core_line_v1) == 4, "ABI alignment");
_Static_assert(offsetof(receipt_core_line_v1, offset) == 0, "ABI offset");
_Static_assert(offsetof(receipt_core_line_v1, length) == 4, "ABI length");

int main(void) {
  uint32_t overlap = 99;
  if (receipt_core_abi_version() != RECEIPT_CORE_ABI_VERSION) return 1;
  return receipt_core_find_overlap_v1(NULL, 0, NULL, 0, NULL, 0, NULL, 0,
      &overlap) != RECEIPT_CORE_OK || overlap != 0;
}
