import { fixupConfigRules } from "@eslint/compat";
import { FlatCompat } from "@eslint/eslintrc";
import js from "@eslint/js";
import prettier from "eslint-plugin-prettier";
import { defineConfig } from "eslint/config";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const compat = new FlatCompat({
  baseDirectory: __dirname,
  recommendedConfig: js.configs.recommended,
  allConfig: js.configs.all,
});

export default defineConfig([
  {
    extends: fixupConfigRules(compat.extends("@react-native", "prettier")),
    plugins: { prettier },
    rules: {
      "react/react-in-jsx-scope": "off",
      "prettier/prettier": "error",
    },
  },
  {
    // Build output. Same set `yarn clean` deletes — Gradle in particular writes
    // JS into its test reports, which lands as hundreds of lint errors the
    // moment anyone runs the Android unit tests.
    ignores: [
      ".yarn/",
      "eslint.config.mjs",
      "node_modules/",
      "lib/",
      "android/build/",
      "example/android/build/",
      "example/android/app/build/",
      "example/ios/build/",
    ],
  },
  {
    files: ["**/*.mjs"],
    languageOptions: {
      ecmaVersion: "latest",
      sourceType: "module",
    },
  },
]);
