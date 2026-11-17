import Foundation

/// Decides which backup archives to delete, without touching the file system.
///
/// Mirrors the shared Kotlin `LibraryBackupPruner.plan`, and both are checked
/// against the same cases in `shared/parity/backup_prune.json`. Archives go
/// oldest first: by age, then by count, then by total size. The newest archive
/// is never deleted, because a limit too strict for even one backup must not
/// leave the user with nothing to restore.
enum LibraryBackupPruner {
    struct Limits: Equatable, Sendable {
        /// 0 means "不按时间删除".
        var retentionDays: Int
        /// 0 or less means no count limit.
        var maximumBackupCount: Int
        /// 0 means "不限容量".
        var maximumTotalBytes: Int64
    }

    struct Plan<Backup> {
        /// In the order they were dropped, oldest first.
        var removed: [Backup]
        /// Newest first.
        var remaining: [Backup]
        /// True when what is left, the newest archive at the least, is still
        /// over the size limit.
        var isOverLimit: Bool
    }

    /// A day is a fixed 24 hours here, as on Android, so the two apps agree
    /// on which archive is too old even across a daylight-saving change.
    static func plan<Backup>(
        _ backups: [Backup],
        limits: Limits,
        now: Date,
        createdAt: (Backup) -> Date,
        size: (Backup) -> Int64
    ) -> Plan<Backup> {
        // Newest first, ties kept in input order, so the last element is
        // always the oldest survivor.
        var remaining = backups.enumerated().sorted { left, right in
            let leftDate = createdAt(left.element)
            let rightDate = createdAt(right.element)
            return leftDate == rightDate ? left.offset < right.offset : leftDate > rightDate
        }.map(\.element)
        var removed: [Backup] = []

        func dropOldest(while shouldDrop: () -> Bool) {
            while remaining.count > 1, shouldDrop() {
                removed.append(remaining.removeLast())
            }
        }
        func totalBytes() -> Int64 {
            remaining.reduce(0) { $0 + size($1) }
        }

        if limits.retentionDays > 0 {
            let cutoff = now.addingTimeInterval(-Double(limits.retentionDays) * 86_400)
            dropOldest { createdAt(remaining[remaining.count - 1]) < cutoff }
        }
        if limits.maximumBackupCount > 0 {
            dropOldest { remaining.count > limits.maximumBackupCount }
        }
        if limits.maximumTotalBytes > 0 {
            dropOldest { totalBytes() > limits.maximumTotalBytes }
        }

        let isOverLimit = limits.maximumTotalBytes > 0 && totalBytes() > limits.maximumTotalBytes
        return Plan(removed: removed, remaining: remaining, isOverLimit: isOverLimit)
    }
}
