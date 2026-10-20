import SwiftUI
import UIKit
import HackAtlasShared

@main
struct HackAtlasApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeHost().ignoresSafeArea()
        }
    }
}

private struct ComposeHost: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        #if DEBUG
        let demoEnabled = true
        #else
        let demoEnabled = false
        #endif
        return MainViewControllerKt.MainViewController(demoEnabled: demoEnabled)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
