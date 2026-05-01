import { TurboModuleRegistry, type TurboModule } from "react-native";

export interface Spec extends TurboModule {
  scan(options: Object): Promise<Object>;
}

export default TurboModuleRegistry.getEnforcing<Spec>("ReceiptScanner");
