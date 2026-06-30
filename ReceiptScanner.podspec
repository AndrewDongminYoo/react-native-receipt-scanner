require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "ReceiptScanner"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = package["homepage"]
  s.license      = package["license"]
  s.authors      = package["author"]

  # Korean OCR via VNRecognizeTextRequest requires iOS 16+. The package does not
  # ship a Latin-only fallback, so the deployment target is pinned at 16.0
  # regardless of React Native's `min_ios_version_supported`.
  s.platforms    = { :ios => "16.0" }
  s.source       = { :git => "https://github.com/AndrewDongminYoo/react-native-receipt-scanner.git", :tag => "#{s.version}" }

  s.source_files = "ios/**/*.{h,m,mm,swift,cpp}"
  s.private_header_files = "ios/RN*.h"

  s.frameworks = "VisionKit", "Vision", "PhotosUI", "ImageIO", "CoreImage", "CoreGraphics", "UniformTypeIdentifiers"

  install_modules_dependencies(s)
end
