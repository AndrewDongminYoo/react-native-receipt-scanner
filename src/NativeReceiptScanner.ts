import { TurboModuleRegistry, type TurboModule } from "react-native";

export interface Spec extends TurboModule {
  // options and result are intentionally untyped for Phase 1;
  // Phase 2 will replace Object with a concrete ReadableMap-compatible shape.
  scan(options: Object): Promise<Object>;
}

export default TurboModuleRegistry.getEnforcing<Spec>("ReceiptScanner");
