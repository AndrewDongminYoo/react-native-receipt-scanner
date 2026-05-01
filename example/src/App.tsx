import { useState } from "react";
import { Button, ScrollView, StyleSheet, Text, View } from "react-native";
import { scan, type ScanReceiptResult } from "react-native-receipt-scanner";

export default function App() {
  const [result, setResult] = useState<ScanReceiptResult | null>(null);

  async function handleScan() {
    try {
      const scanResult = await scan({ source: "camera", ocr: true });
      setResult(scanResult);
    } catch (e) {
      console.error("scan failed", e);
    }
  }

  return (
    <View style={styles.container}>
      <Button title="Scan Receipt" onPress={handleScan} />
      {result && (
        <ScrollView style={styles.result}>
          <Text>Status: {result.status}</Text>
          <Text>Images: {result.images.length}</Text>
          {result.images.map((img, i) => (
            <View key={i}>
              <Text>URI: {img.uri}</Text>
              <Text>
                {img.width}×{img.height} ({img.fileSize} bytes)
              </Text>
              {img.ocrText ? <Text>OCR: {img.ocrText}</Text> : null}
            </View>
          ))}
        </ScrollView>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    alignItems: "center",
    justifyContent: "center",
    padding: 16,
  },
  result: {
    marginTop: 16,
    width: "100%",
  },
});
