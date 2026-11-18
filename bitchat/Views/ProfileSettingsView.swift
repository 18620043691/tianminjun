#if os(iOS)
import PhotosUI
import ImageIO
import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct ProfileSettingsView: View {
    @EnvironmentObject private var appChromeModel: AppChromeModel
    @ThemedPalette private var palette
    @AppStorage("profile.avatarData") private var avatarData = Data()
    @State private var selectedPhoto: PhotosPickerItem?
    @State private var photoError: String?
    @State private var draftNickname = ""
    @State private var nicknameError: String?

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            PhotosPicker(selection: $selectedPhoto, matching: .images) {
                Group {
                    if let image = UIImage(data: avatarData) {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFill()
                    } else {
                        Image(systemName: "person.crop.circle.fill")
                            .resizable()
                            .foregroundStyle(palette.accent.opacity(0.65))
                    }
                }
                .frame(width: 64, height: 64)
                .clipShape(Circle())
                .overlay(Circle().stroke(palette.divider, lineWidth: 1))
                .accessibilityLabel("Choose profile photo")
            }
            .buttonStyle(.plain)

            VStack(alignment: .leading, spacing: 8) {
                TextField("Display name", text: $draftNickname)
                    .textFieldStyle(.roundedBorder)
                    .textInputAutocapitalization(.words)
                    .accessibilityLabel("Profile display name")
                    .onChange(of: draftNickname) { _ in nicknameError = nil }
                if let nicknameError {
                    Text(nicknameError)
                        .bitchatFont(size: 11)
                        .foregroundColor(palette.alertRed)
                }
                Button("Save name") {
                    let value = draftNickname.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !value.isEmpty, value.utf8.count <= 64 else {
                        nicknameError = "Use a name between 1 and 64 UTF-8 bytes."
                        return
                    }
                    appChromeModel.setNickname(value)
                    appChromeModel.validateAndSaveNickname()
                    draftNickname = appChromeModel.nickname
                    nicknameError = nil
                }
                .buttonStyle(.plain)
                .foregroundColor(palette.accent)
                Text("Your name is announced to nearby peers. The avatar is stored on this device only.")
                    .bitchatFont(size: 11)
                    .foregroundColor(palette.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                if let photoError {
                    Text(photoError)
                        .bitchatFont(size: 11)
                        .foregroundColor(palette.alertRed)
                }
            }
        }
        .onAppear { draftNickname = appChromeModel.nickname }
        .task(id: selectedPhoto) {
            guard let selectedPhoto else { return }
            do {
                guard let data = try await selectedPhoto.loadTransferable(type: Data.self),
                      let safeImage = Self.thumbnailJPEG(from: data) else {
                    photoError = "Choose a supported image under 20 MB. The saved thumbnail is limited to 512 KB."
                    return
                }
                avatarData = safeImage
                photoError = nil
            } catch {
                photoError = "Could not load that photo."
            }
        }
    }

    private static func thumbnailJPEG(from data: Data) -> Data? {
        guard data.count <= 20 * 1024 * 1024 else { return nil }
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: 512,
            kCGImageSourceCreateThumbnailWithTransform: true
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        for quality in [0.82, 0.68, 0.52, 0.36] {
            let output = NSMutableData()
            guard let destination = CGImageDestinationCreateWithData(
                output,
                UTType.jpeg.identifier as CFString,
                1,
                nil
            ) else { return nil }
            CGImageDestinationAddImage(destination, image, [
                kCGImageDestinationLossyCompressionQuality: quality
            ] as CFDictionary)
            guard CGImageDestinationFinalize(destination) else { return nil }
            if output.length <= 512 * 1024 {
                return output as Data
            }
        }
        return nil
    }
}
#endif
