import Foundation
import Security
import CryptoKit

enum NodiInternet {
    static let service = "dev.nodi.internet.v1"
    static let prefix = "nodi-internet1-"
    /// Snapshot mode (NODI_SNAPSHOT) renders a throwaway window: it must never read or
    /// change the owner's Keychain, which would also ask for the login password.
    static let isolated = ProcessInfo.processInfo.environment["NODI_SNAPSHOT"] != nil
    static var ownPin: String? {
        let path = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support/Nodi/Devices/identity.json")
        guard let data = try? Data(contentsOf:path),
              let object = try? JSONSerialization.jsonObject(with:data) as? [String:Any],
              let encoded = object["Certificate"] as? String, let certificate = Data(base64Encoded:encoded) else {return nil}
        return SHA256.hash(data:certificate).map{String(format:"%02x",$0)}.joined()
    }
    /// Relay credentials live next to the device identity (Devices/ is 0700, the file 0600).
    /// Alpha 6–9 kept them in the Keychain, but an ad-hoc signed app changes its signature on
    /// every update and macOS then asks for the login password; a private file avoids that.
    static var storeURL: URL {
        FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support/Nodi/Devices/internet.json")
    }
    nonisolated(unsafe) private static var keychainChecked = false
    private static let keychainQuery: [String:Any] = [kSecClass as String:kSecClassGenericPassword,kSecAttrService as String:service,kSecAttrAccount as String:"relay"]
    private static func parse(_ data: Data) -> [String:String]? {
        guard let config = try? JSONSerialization.jsonObject(with:data) as? [String:String],
              let token = config["token"], token.range(of:"^[a-f0-9]{64}$",options:.regularExpression) != nil,
              let pin = config["fingerprint"], pin == ownPin else { return nil }
        return ["fingerprint":pin,"token":token]
    }
    private static func store(_ config: [String:String]) throws {
        let dir = storeURL.deletingLastPathComponent()
        try FileManager.default.createDirectory(at:dir,withIntermediateDirectories:true,attributes:[.posixPermissions:0o700])
        let data = try JSONSerialization.data(withJSONObject:config)
        let temp = dir.appendingPathComponent(".internet.json.\(UUID().uuidString)")
        guard FileManager.default.createFile(atPath:temp.path,contents:data,attributes:[.posixPermissions:0o600]) else {
            throw NSError(domain:"Nodi",code:2,userInfo:[NSLocalizedDescriptionKey:"Не вдалося зберегти інтернет-доступ"])
        }
        guard rename(temp.path, storeURL.path) == 0 else {
            try? FileManager.default.removeItem(at:temp)
            throw NSError(domain:NSPOSIXErrorDomain,code:Int(errno))
        }
    }
    static func load() -> [String:String]? {
        if isolated { return nil }
        if let data = try? Data(contentsOf:storeURL) { return parse(data) }
        // One-time move from the Keychain of Alpha 6–9: at most one macOS prompt per launch,
        // and none again once the file exists.
        guard !keychainChecked else { return nil }
        keychainChecked = true
        var query = keychainQuery; query[kSecReturnData as String] = true
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary,&result) == errSecSuccess,
              let data = result as? Data, let config = parse(data) else { return nil }
        try? store(config)
        return config
    }
    static func clear() {
        if isolated { return }
        try? FileManager.default.removeItem(at:storeURL)
        SecItemDelete(keychainQuery as CFDictionary)
    }
    static func activate(_ code:String) throws {
        func invalid()->NSError {NSError(domain:"Nodi",code:1,userInfo:[NSLocalizedDescriptionKey:"Код не підходить цьому пристрою."])}
        guard code.hasPrefix(prefix),code.count<2048 else{throw invalid()}
        var base=String(code.dropFirst(prefix.count)).replacingOccurrences(of:"-",with:"+").replacingOccurrences(of:"_",with:"/")
        base += String(repeating:"=",count:(4-base.count%4)%4)
        guard let data=Data(base64Encoded:base), let config=parse(data) else{throw invalid()}
        if isolated { return }
        try store(config)
    }
    static var testInstall:Bool {Bundle.main.object(forInfoDictionaryKey:"NodiTestInstall") as? Bool ?? true}
    static func request(_ path:String, payload:[String:Any], completion: @escaping (Bool)->Void) {
        guard let token=load()?["token"],let url=URL(string:"https://nodi-relay.igor-shura2011faik.workers.dev"+path),let body=try? JSONSerialization.data(withJSONObject:payload) else{completion(false);return}
        var request=URLRequest(url:url);request.httpMethod="POST";request.timeoutInterval=15
        request.setValue("Bearer "+token,forHTTPHeaderField:"Authorization");request.setValue("application/json",forHTTPHeaderField:"Content-Type");request.httpBody=body
        URLSession.shared.dataTask(with:request){_,response,error in
            completion(error==nil && (response as? HTTPURLResponse)?.statusCode==200)
        }.resume()
    }
    static func usage(_ event:String) {
        guard !testInstall && UserDefaults.standard.bool(forKey:"nodi.usageConsent") else{return}
        request("/v1/usage",payload:["consent":true,"platform":"macos","event":event]){_ in}
    }

}
