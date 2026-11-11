import SwiftUI
import UIKit
import HackAtlasShared

@main
struct HackAtlasApp: App {
    @UIApplicationDelegateAdaptor(CatalogAppDelegate.self) private var delegate
    @Environment(\.scenePhase) private var scenePhase
    var body: some Scene {
        WindowGroup {
            ComposeHost().ignoresSafeArea()
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active {
                MainViewControllerKt.authOnForeground()
            } else {
                MainViewControllerKt.authOnBackground()
            }
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
        #if DEBUG && HACKATLAS_N2
        let configuredMode = Bundle.main.object(forInfoDictionaryKey: "HackAtlasAccountMode") as? String
        let accountMode = configuredMode == "N2_REQUIRED" ? "N2_REQUIRED" : ""
        #else
        let accountMode = "LEGACY"
        #endif
        return MainViewControllerKt.MainViewController(
            isDebug: isDebug, sourceMode: source, apiBaseUrl: baseUrl, accountMode: accountMode
        )
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

private final class CatalogAppDelegate: NSObject, UIApplicationDelegate {
    func applicationWillTerminate(_ application: UIApplication) {
        MainViewControllerKt.closeCatalogRuntime()
    }
}
