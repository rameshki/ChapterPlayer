import SwiftUI
import Shared

@main
struct ChapterPlayerApp: App {
    init() {
        // Starts Koin (the shared dependency container) once, before the first screen asks for anything.
        KoinIosKt.doInitKoin()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .ignoresSafeArea()
        }
    }
}
