import Foundation

enum AsyncTimeoutResult<T> {
    case success(T)
    case timedOut
}

func withAsyncTimeout<T>(
    seconds: TimeInterval,
    operation: @escaping @Sendable () async -> T
) async -> AsyncTimeoutResult<T> {
    await withTaskGroup(of: AsyncTimeoutResult<T>.self) { group in
        group.addTask { .success(await operation()) }
        group.addTask {
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            return .timedOut
        }
        let first = await group.next()
        group.cancelAll()
        return first ?? .timedOut
    }
}
