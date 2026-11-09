cmake_minimum_required(VERSION 3.22.1)
project(receipt_overlap_pilot LANGUAGES CXX)

add_library(receipt_overlap_pilot STATIC
  ../cpp/overlap_pilot/ReceiptOverlapPilot.cpp
  ../cpp/receipt_core/receipt_core.cpp)
target_include_directories(receipt_overlap_pilot PUBLIC ../cpp/overlap_pilot)
target_compile_features(receipt_overlap_pilot PUBLIC cxx_std_20)
set_target_properties(receipt_overlap_pilot PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_link_libraries(receipt_overlap_pilot react_codegen_ReceiptScannerSpec)
