import Foundation

// Current receivers bind IPv4. Never resolve names or infer a port here.
enum NodiEndpoint {
    static func normalize(_ raw: String) -> String? {
        let parts = raw.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: ":", omittingEmptySubsequences: false)
        guard parts.count == 2 else { return nil }
        let octets = parts[0].split(separator: ".", omittingEmptySubsequences: false)
        func digits(_ s: Substring, max: Int) -> Bool {
            !s.isEmpty && s.utf8.count <= max && s.utf8.allSatisfy { $0 >= 48 && $0 <= 57 }
        }
        guard octets.count == 4, octets.allSatisfy({ digits($0, max: 3) }),
              digits(parts[1], max: 5), let port = Int(parts[1]), (1...65535).contains(port) else { return nil }
        let values = octets.compactMap { Int($0) }
        guard values.count == 4, values.allSatisfy({ (0...255).contains($0) }),
              (1...223).contains(values[0]) else { return nil }
        return values.map(String.init).joined(separator: ".") + ":" + String(port)
    }
}
