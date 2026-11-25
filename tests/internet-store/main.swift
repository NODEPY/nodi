// Checks macos/NodiInternet.swift file storage against a fake home folder
// (CFFIXED_USER_HOME). It never reaches the Keychain: the file always exists first.
import Foundation
import CryptoKit
func check(_ ok: Bool, _ name: String) { print((ok ? "PASS " : "FAIL ") + name); if !ok { exit(1) } }
let devices = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support/Nodi/Devices")
let cert = Data("fake-certificate".utf8)
try! JSONSerialization.data(withJSONObject: ["Certificate": cert.base64EncodedString()]).write(to: devices.appendingPathComponent("identity.json"))
let pin = SHA256.hash(data: cert).map { String(format: "%02x", $0) }.joined()
check(NodiInternet.ownPin == pin, "fake identity is used")
func code(_ config: [String: String]) -> String {
    NodiInternet.prefix + (try! JSONSerialization.data(withJSONObject: config)).base64EncodedString()
        .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
}
let token = String(repeating: "ab", count: 32)
// activate writes the file (never reaching the Keychain), load reads it back
try NodiInternet.activate(code(["fingerprint": pin, "token": token]))
let attrs = try FileManager.default.attributesOfItem(atPath: NodiInternet.storeURL.path)
check((attrs[.posixPermissions] as? NSNumber)?.intValue == 0o600, "internet.json is 0600")
check(NodiInternet.load()?["token"] == token, "load returns the stored token")
check(try! FileManager.default.contentsOfDirectory(atPath: devices.path).filter { $0.hasPrefix(".internet") }.isEmpty, "no temp files left")
// wrong device or malformed codes are rejected and do not overwrite
check((try? NodiInternet.activate(code(["fingerprint": String(repeating: "0", count: 64), "token": token]))) == nil, "other device rejected")
check((try? NodiInternet.activate(code(["fingerprint": pin, "token": "short"]))) == nil, "bad token rejected")
check(NodiInternet.load()?["token"] == token, "rejected codes keep the old token")
// a file for another identity is ignored (e.g. after re-creating identity.json)
try! JSONSerialization.data(withJSONObject: ["fingerprint": String(repeating: "1", count: 64), "token": token]).write(to: NodiInternet.storeURL)
check(NodiInternet.load() == nil, "file for another identity ignored")
print("ALL PASS")
