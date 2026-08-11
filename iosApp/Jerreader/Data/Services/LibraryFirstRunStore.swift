import Foundation

/// Remembers whether this installation has ever held a book.
///
/// The shelf's 从备份恢复 entry exists for one situation: a reinstall, where an
/// empty shelf is not a choice the user made. Once a book has been imported the
/// shelf is a shelf again, and an empty one only means the user emptied it —
/// keeping a restore button there permanently turns a first-run affordance into
/// furniture. Restoring stays reachable in 设置 → 备份中心 either way.
struct LibraryFirstRunStore {
    private let defaults: UserDefaults
    private let key = "library.hasEverHeldABook"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// True until the first book lands on the shelf, and false forever after —
    /// including on an empty shelf the user cleared themselves.
    var isFreshInstallation: Bool {
        !defaults.bool(forKey: key)
    }

    /// Called with the shelf's current count rather than as a bare flag, so the
    /// caller cannot accidentally retire the first-run state on an empty shelf.
    func noteLibrary(bookCount: Int) {
        guard bookCount > 0, isFreshInstallation else { return }
        defaults.set(true, forKey: key)
    }
}
