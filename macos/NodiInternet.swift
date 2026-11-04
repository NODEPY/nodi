import Foundation
import Security
import CryptoKit

enum NodiInternet {
    static let service = "dev.nodi.internet.v1"
    static let prefix = "nodi-internet1-"
    static var ownPin: String? {
        let path = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support/Nodi/Devices/identity.json")
        guard let data = try? Data(contentsOf:path),
              let object = try? JSONSerialization.jsonObject(with:data) as? [String:Any],
              let encoded = object["Certificate"] as? String, let certificate = Data(base64Encoded:encoded) else {return nil}
        return SHA256.hash(data:certificate).map{String(format:"%02x",$0)}.joined()
    }
    static func load() -> [String:String]? {
        let query:[String:Any] = [kSecClass as String:kSecClassGenericPassword,kSecAttrService as String:service,kSecAttrAccount as String:"relay",kSecReturnData as String:true]
        var result:CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary,&result)==errSecSuccess,
              let data=result as? Data,let config=try? JSONSerialization.jsonObject(with:data) as? [String:String],
              config["fingerprint"]==ownPin else{return nil}
        return config
    }
    static func clear() {
        SecItemDelete([kSecClass as String:kSecClassGenericPassword,kSecAttrService as String:service,kSecAttrAccount as String:"relay"] as CFDictionary)
    }
    static func activate(_ code:String) throws {
        func invalid()->NSError {NSError(domain:"Nodi",code:1,userInfo:[NSLocalizedDescriptionKey:"Код не підходить цьому пристрою."])}
        guard code.hasPrefix(prefix),code.count<2048 else{throw invalid()}
        var base=String(code.dropFirst(prefix.count)).replacingOccurrences(of:"-",with:"+").replacingOccurrences(of:"_",with:"/")
        base += String(repeating:"=",count:(4-base.count%4)%4)
        guard let data=Data(base64Encoded:base),let config=try? JSONSerialization.jsonObject(with:data) as? [String:String],
              let token=config["token"],token.range(of:"^[a-f0-9]{64}$",options:.regularExpression) != nil,
              let pin=config["fingerprint"],pin==ownPin else{throw invalid()}
        let clean=try JSONSerialization.data(withJSONObject:["fingerprint":pin,"token":token])
        let query:[String:Any]=[kSecClass as String:kSecClassGenericPassword,kSecAttrService as String:service,kSecAttrAccount as String:"relay"]
        var status=SecItemUpdate(query as CFDictionary,[kSecValueData as String:clean] as CFDictionary)
        if status==errSecItemNotFound { var item=query;item[kSecValueData as String]=clean;status=SecItemAdd(item as CFDictionary,nil) }
        guard status==errSecSuccess else{throw NSError(domain:NSOSStatusErrorDomain,code:Int(status))}
    }
}
