import Foundation

let accepted = [
    "100.64.0.2:7331": "100.64.0.2:7331",
    " 192.168.001.002:00080\n": "192.168.1.2:80",
    "127.0.0.1:1": "127.0.0.1:1",
    "223.255.255.255:65535": "223.255.255.255:65535"
]
for (input, expected) in accepted {
    precondition(NodiEndpoint.normalize(input) == expected, "Rejected valid endpoint: \(input)")
}
let rejected = ["", "example.com:7331", "https://100.64.0.2:7331", "100.64.0.2",
                "100.64.0.2:0", "100.64.0.2:65536", "100.64.0.2:+80", "100.64.0.2:8 0",
                "256.1.2.3:80", "0.0.0.0:80", "224.0.0.1:80", "255.255.255.255:80",
                "1.2.3:80", "1..2.3:80", "1.2.3.4:80/path", "[::1]:80", "1.2.3.4:８０"]
for input in rejected { precondition(NodiEndpoint.normalize(input) == nil, "Accepted invalid endpoint: \(input)") }
print("PASS: Swift manual endpoint validation (\(accepted.count + rejected.count) cases)")
