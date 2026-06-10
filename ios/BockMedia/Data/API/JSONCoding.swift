import Foundation

enum JSONCoding {
    static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        return d
    }()

    static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        return e
    }()

    static func decode<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        try decoder.decode(type, from: data)
    }

    static func encodeJSONObject(_ object: [String: Any]) throws -> Data {
        try JSONSerialization.data(withJSONObject: object)
    }
}
