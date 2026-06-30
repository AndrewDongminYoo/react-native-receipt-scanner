module.exports = {
  preset: "@react-native/jest-preset",
  modulePathIgnorePatterns: ["<rootDir>/example/node_modules", "<rootDir>/lib/"],
  testPathIgnorePatterns: ["/node_modules/", "<rootDir>/src/__tests__/fixtures/"],
};
