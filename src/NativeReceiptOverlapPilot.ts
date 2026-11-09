import type { TurboModule } from "react-native";
import { TurboModuleRegistry } from "react-native";

/** Internal experiment only; deliberately absent from the public entry point. */
export interface Spec extends TurboModule {
  getAbiVersion(): number;
  findOverlap(
    leftUnits: ReadonlyArray<number>,
    leftRanges: ReadonlyArray<number>,
    rightUnits: ReadonlyArray<number>,
    rightRanges: ReadonlyArray<number>
  ): number;
}

export default TurboModuleRegistry.getEnforcing<Spec>("ReceiptOverlapPilot");
