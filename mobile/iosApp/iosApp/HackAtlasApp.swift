import SwiftUI
import UIKit
import HackAtlasShared

@main
struct HackAtlasApp: App {
    @UIApplicationDelegateAdaptor(CatalogAppDelegate.self) private var delegate
    var body: some Scene {
        WindowGroup {
            ComposeHost().ignoresSafeArea()
        }
    }
}

private struct ComposeHost: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        #if DEBUG
        let isDebug = true
        #else
        let isDebug = false
        #endif
        let source = Bundle.main.object(forInfoDictionaryKey: "HackAtlasCatalogSource") as? String ?? ""
        let baseUrl = Bundle.main.object(forInfoDictionaryKey: "HackAtlasApiBaseUrl") as? String ?? ""
        return MainViewControllerKt.MainViewController(isDebug: isDebug, sourceMode: source, apiBaseUrl: baseUrl)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

private final class CatalogAppDelegate: NSObject, UIApplicationDelegate {
    func applicationWillTerminate(_ application: UIApplication) {
        MainViewControllerKt.closeCatalogRuntime()
    }
}
