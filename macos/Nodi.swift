import AppKit
import Foundation
import CoreImage
import ImageIO
import QuartzCore
import Darwin

// The Go process owns networking. AppKit owns windows and file selection.
struct Invite: Decodable {
    let address: String
    let code: String
}
struct CoreEvent: Decodable {
    let type: String
    let message: String?
    let path: String?
    let invites: [Invite]?
    var id: String? = nil
    var name: String? = nil
    var compare: String? = nil
    var code: String? = nil
    var devices: [SavedDevice]? = nil
}

final class FlippedDocumentView: NSView { override var isFlipped: Bool { true } }

final class CardView: NSView {
    override var wantsUpdateLayer: Bool { true }
    override func updateLayer() {
        layer?.backgroundColor = NSColor(srgbRed: 25/255, green: 28/255, blue: 35/255, alpha: 1).cgColor
        layer?.borderColor = NSColor.white.withAlphaComponent(0.06).cgColor
        layer?.borderWidth = 1
        layer?.cornerRadius = 16
    }
}

// Native Core Image QR rendering. No code or image is sent to a service.
final class InviteQRView: NSView {
    private let qrImage: CGImage
    init?(code: String) {
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(Data(code.utf8), forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage,
              let image = CIContext().createCGImage(output, from: output.extent) else { return nil }
        qrImage = image
        super.init(frame: .zero)
        setAccessibilityElement(true)
        setAccessibilityLabel("QR-код для підключення телефона до Nodi")
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }
    override var isOpaque: Bool { true }
    override func draw(_ dirtyRect: NSRect) {
        NSColor.white.setFill()
        bounds.fill()
        guard let context = NSGraphicsContext.current?.cgContext else { return }
        // Whole-point modules are sharp on both standard and Retina displays.
        // Four white modules on each side are the QR quiet zone.
        let scale = floor(min(bounds.width, bounds.height) / CGFloat(qrImage.width + 8))
        guard scale >= 1 else { return }
        let size = CGFloat(qrImage.width) * scale
        let rect = CGRect(x: floor((bounds.width - size) / 2),
                          y: floor((bounds.height - size) / 2), width: size, height: size)
        context.saveGState()
        context.interpolationQuality = .none
        context.setShouldAntialias(false)
        context.draw(qrImage, in: rect)
        context.restoreGState()
    }
}

// Read complete UTF-8 lines off the UI thread. All callbacks run on the main queue.
final class CoreTask {
    private let process = Process()
    private var inputHandle: FileHandle?
    func start(arguments: [String], secret: String? = nil, keepInput: Bool = false,
               onEvent: @escaping (CoreEvent) -> Void,
               onExit: @escaping (Int32) -> Void) throws {
        guard let binaryDirectory = Bundle.main.executableURL?.deletingLastPathComponent() else {
            throw NSError(domain: "Nodi", code: 1, userInfo: [NSLocalizedDescriptionKey: "Не знайдено ресурси Nodi.app."])
        }
        let executable = binaryDirectory.appendingPathComponent("nodi-core")
        guard FileManager.default.isExecutableFile(atPath: executable.path) else {
            throw NSError(domain: "Nodi", code: 2, userInfo: [NSLocalizedDescriptionKey: "Не знайдено Go-ядро. Запусти scripts/run-macos.sh повторно."])
        }
        process.executableURL = executable
        process.arguments = arguments
        let output = Pipe()
        process.standardOutput = output
        process.standardError = output
        let input = Pipe()
        process.standardInput = input
        try process.run()
        if let secret = secret { input.fileHandleForWriting.write(Data(secret.utf8)) }
        if keepInput { inputHandle = input.fileHandleForWriting } else { input.fileHandleForWriting.closeFile() }
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            var pending = Data()
            func deliver(_ data: Data) {
                guard !data.isEmpty else { return }
                let event: CoreEvent
                if let decoded = try? JSONDecoder().decode(CoreEvent.self, from: data) {
                    event = decoded
                } else {
                    let text = String(decoding: data, as: UTF8.self)
                    event = CoreEvent(type: "error", message: text, path: nil, invites: nil)
                }
                DispatchQueue.main.async { onEvent(event) }
            }
            while true {
                let chunk = output.fileHandleForReading.availableData
                if chunk.isEmpty { break }
                pending.append(chunk)
                while let newline = pending.firstIndex(of: 10) {
                    deliver(Data(pending[..<newline]))
                    pending.removeSubrange(...newline)
                }
            }
            deliver(pending)
            output.fileHandleForReading.closeFile()
            process.waitUntilExit()
            let status = process.terminationStatus
            DispatchQueue.main.async { [self] in try? inputHandle?.close(); inputHandle = nil; onExit(status) }
        }
    }
    func command(_ type: String, pin: String? = nil) {
        guard let handle = self.inputHandle, self.process.isRunning else { return }
        var value: [String: String] = ["type": type]
        if let pin = pin { value["pin"] = pin }
        guard var data = try? JSONSerialization.data(withJSONObject: value) else { return }
        data.append(10); try? handle.write(contentsOf: data)
    }
    func stop() { if process.isRunning { process.terminate() } }
}

struct SavedDevice: Decodable {
    let name: String
    let address: String
    let fingerprint: String
    let token: String
    func code(address: String) -> String? {
        guard let data = try? JSONSerialization.data(withJSONObject: ["address": address, "fingerprint": self.fingerprint, "token": self.token]) else { return nil }
        return "nodi1-" + data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
}

final class NodiSetup:NSObject {
    private let panel:NSPanel
    private let preferences=UserDefaults.standard
    private let first:Bool
    private let oldLanguage:String
    private var language:String
    private let heading=NodiLabel(wrappingLabelWithString:"")
    private let subtitle=NodiLabel(wrappingLabelWithString:"")
    private let languageLabel=NodiLabel(labelWithString:"")
    private let previews=NSButton(checkboxWithTitle:"",target:nil,action:nil)
    private let motion=NSButton(checkboxWithTitle:"",target:nil,action:nil)
    private let done=NodiButton(title:"",target:nil,action:nil)
    private let cancel=NodiButton(title:"",target:nil,action:nil)
    private var choices:[NodiButton]=[]
    init(first:Bool) {
        self.first=first
        self.oldLanguage=UserDefaults.standard.string(forKey:"nodi.language") ?? "en"
        self.language=self.oldLanguage
        self.panel=NSPanel(contentRect:NSRect(x:0,y:0,width:440,height:530),styleMask:[.titled,.fullSizeContentView],backing:.buffered,defer:false)
        super.init()
        self.panel.titleVisibility = .hidden;self.panel.titlebarAppearsTransparent=true;self.panel.isReleasedWhenClosed=false
        self.panel.appearance=NSAppearance(named:.darkAqua);self.panel.backgroundColor=NSColor(srgbRed:16/255,green:18/255,blue:23/255,alpha:1)
        let root=NSView();self.panel.contentView=root
        let brand=NodiLabel(labelWithString:"nodi");brand.font = .systemFont(ofSize:24,weight:.bold)
        let hero=NSImageView();hero.image=NSImage(contentsOfFile:Bundle.main.path(forResource:"NodiMark",ofType:"png") ?? "");hero.contentTintColor=NSColor(srgbRed:160/255,green:177/255,blue:1,alpha:1);hero.imageScaling = .scaleProportionallyUpOrDown
        hero.heightAnchor.constraint(equalToConstant:64).isActive=true;hero.widthAnchor.constraint(equalToConstant:64).isActive=true
        self.heading.font = .systemFont(ofSize:25,weight:.semibold)
        self.subtitle.font = .systemFont(ofSize:13);self.subtitle.textColor = .secondaryLabelColor
        self.languageLabel.font = .systemFont(ofSize:12,weight:.medium);self.languageLabel.textColor = .secondaryLabelColor
        let options=NSStackView();options.orientation = .vertical;options.spacing=6
        for (i,name) in ["English","Українська","Русский"].enumerated() {
            let button=NodiButton(title:name,target:self,action:#selector(selectLanguage(_:)));button.tag=i
            button.heightAnchor.constraint(equalToConstant:38).isActive=true
            options.addArrangedSubview(button);button.widthAnchor.constraint(equalTo:options.widthAnchor).isActive=true;self.choices.append(button)
        }
        self.previews.state=self.preferences.object(forKey:"nodi.previews")==nil || self.preferences.bool(forKey:"nodi.previews") ? .on : .off
        self.motion.state=self.preferences.bool(forKey:"nodi.reduceMotion") ? .on : .off
        self.done.primary=true;self.done.target=self;self.done.action=#selector(save);self.done.keyEquivalent="\r"
        self.cancel.target=self;self.cancel.action=#selector(abort);self.cancel.keyEquivalent="\u{1b}"
        let actions=NSStackView(views:first ? [self.done] : [self.cancel,self.done]);actions.distribution = .fillEqually;actions.spacing=10
        let stack=NSStackView(views:[brand,hero,self.heading,self.subtitle,self.languageLabel,options,self.previews,self.motion,actions]);stack.orientation = .vertical;stack.alignment = .leading;stack.spacing=14
        stack.setCustomSpacing(22,after:brand);stack.setCustomSpacing(5,after:self.heading);stack.setCustomSpacing(20,after:self.subtitle);stack.setCustomSpacing(20,after:options);stack.setCustomSpacing(24,after:self.motion)
        stack.translatesAutoresizingMaskIntoConstraints=false;root.addSubview(stack)
        NSLayoutConstraint.activate([stack.leadingAnchor.constraint(equalTo:root.leadingAnchor,constant:32),stack.trailingAnchor.constraint(equalTo:root.trailingAnchor,constant:-32),stack.topAnchor.constraint(equalTo:root.topAnchor,constant:30)])
        for view in [self.heading,self.subtitle,options,actions] as [NSView] {view.widthAnchor.constraint(equalTo:stack.widthAnchor).isActive=true}
        self.refresh()
        root.layoutSubtreeIfNeeded();self.panel.setContentSize(NSSize(width:440,height:max(530,stack.fittingSize.height+64)))
    }
    private func refresh() {
        self.heading.stringValue=self.first ? "Твої файли. Напряму." : "Параметри"
        self.subtitle.stringValue="Налаштуй Nodi під себе";self.languageLabel.stringValue="Мова"
        self.previews.title=NodiL("Прев’ю файлів");self.motion.title=NodiL("Менше анімацій")
        self.done.title=self.first ? "Продовжити" : "Готово";self.cancel.title="Скасувати"
        for (i,button) in self.choices.enumerated() {let selected=["en","uk","ru"][i]==self.language;button.title=["English","Українська","Русский"][i]+(selected ? "   ✓" : "");button.primary=selected}
    }
    @objc private func selectLanguage(_ sender:NSButton) {self.language=["en","uk","ru"][sender.tag];self.preferences.set(self.language,forKey:"nodi.language");self.refresh()}
    @objc private func save() {
        self.preferences.set(self.language,forKey:"nodi.language");self.preferences.set(self.previews.state == .on,forKey:"nodi.previews");self.preferences.set(self.motion.state == .on,forKey:"nodi.reduceMotion");self.preferences.set(true,forKey:"nodi.setup")
        NSApp.stopModal()
    }
    @objc private func abort() {self.preferences.set(self.oldLanguage,forKey:"nodi.language");NSApp.stopModal()}
    func present(parent:NSWindow?) {
        NSApp.activate(ignoringOtherApps:true)
        if let parent=parent {parent.addChildWindow(self.panel,ordered:.above);self.panel.setFrameOrigin(NSPoint(x:parent.frame.midX-self.panel.frame.width/2,y:parent.frame.midY-self.panel.frame.height/2))} else {self.panel.center()}
        self.panel.makeKeyAndOrderFront(nil);NSApp.runModal(for:self.panel)
        parent?.removeChildWindow(self.panel);self.panel.orderOut(nil)
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    private var window: NSWindow!
    private var qrPanel: NSPanel?
    private var logPanel: NSScrollView?
    private let lastEvent = NodiLabel(labelWithString: "")
    private var qrTimer: Timer?
    private var receiver: CoreTask?
    private var sender: CoreTask?
    private var saved: [SavedDevice] = []
    private var discovered: [NearbyDevice] = []
    private var invites: [Invite] = []
    private var selectedFiles: [URL] = []
    private var pendingFiles: [URL] = []
    private var batchTotal=0
    private var batchSent=0
    private var activeSendPin: String?
    private var pendingQR = false
    private var stopping = false
    private var sendConfirmed = false
    private var sendCancelled = false
    private let browser = NearbyBrowser()
    private let preferences = UserDefaults.standard
    private lazy var inbox: URL = {
        if let path = self.preferences.string(forKey: "inbox") { return URL(fileURLWithPath: path, isDirectory: true) }
        return FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Downloads/Nodi", isDirectory: true)
    }()
    private lazy var deviceDirectory: URL = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support/Nodi/Devices", isDirectory: true)
    private let receiveButton = NodiButton(title: "Увімкнути приймання", target: nil, action: nil)
    private let receiveStatus = NodiLabel(labelWithString: "Приймання вимкнене")
    private let folderLabel = NodiLabel(labelWithString: "")
    private let folderButton = NodiButton(title: "Змінити папку…", target: nil, action: nil)
    private let devicePicker = NSPopUpButton(frame: .zero, pullsDown: false)
    private let addButton = NodiButton(title: "Додати телефон — QR", target: nil, action: nil)
    private let forgetButton = NodiButton(title: "Видалити пристрій", target: nil, action: nil)
    private let addressPicker = NSPopUpButton(frame: .zero, pullsDown: false)
    private let deviceHint = NodiLabel(wrappingLabelWithString: "Скануй QR камерою телефона один раз — потім надсилай файли в обидва боки.")
    private let chooseButton = NodiButton(title: "Вибрати файл…", target: nil, action: nil)
    private let fileLabel = NodiLabel(labelWithString: "Файл не вибрано")
    private let sendButton = NodiButton(title: "Надіслати файл", target: nil, action: nil)
    private let cancelButton = NodiButton(title: "Скасувати", target: nil, action: nil)
    private let sendStatus = NodiLabel(wrappingLabelWithString: "Вибери файл")
    private let activity = NSTextView()
    private let spinner = NSProgressIndicator()
    func applicationDidFinishLaunching(_ notification: Notification) {
        if !self.preferences.bool(forKey:"nodi.setup") { self.showSetup(first:true) }
        self.makeMenu(); self.buildWindow()
        if let root=self.window.contentView { self.translateUI(root) }
        self.browser.onChange = { [weak self] devices in self?.discovered = devices; self?.renderDevices() }
        self.browser.onError = { [weak self] in self?.log("Пошук недоступний. Перевір дозвіл локальної мережі та Wi-Fi.") }
        self.browser.start()
        // Only paired-device capabilities can upload. New devices require a displayed QR.
        self.toggleReceive()
        NSApp.activate(ignoringOtherApps: true)
    }
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        if self.sender != nil {
            let alert = NSAlert(); alert.messageText = NodiL("Зупинити передачу й закрити Nodi?")
            alert.addButton(withTitle: NodiL("Залишитися")); alert.addButton(withTitle: NodiL("Закрити"))
            if alert.runModal() != .alertSecondButtonReturn { return .terminateCancel }
        }
        return .terminateNow
    }
    func applicationWillTerminate(_ notification: Notification) { self.dismissPreview(); self.closeQR(); self.sender?.stop(); self.receiver?.stop(); self.browser.stop() }
    private func makeMenu() {
        let menu = NSMenu(); let appItem = NSMenuItem(); menu.addItem(appItem)
        let appMenu = NSMenu(); appItem.submenu = appMenu
        appMenu.addItem(withTitle: NodiL("Завершити Nodi"), action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        let edit = NSMenuItem(title: "Редагування", action: nil, keyEquivalent: ""); let sub = NSMenu(); edit.submenu = sub; menu.addItem(edit)
        sub.addItem(withTitle: NodiL("Копіювати"), action: #selector(NSText.copy(_:)), keyEquivalent: "c")
        sub.addItem(withTitle: NodiL("Вставити"), action: #selector(NSText.paste(_:)), keyEquivalent: "v")
        sub.addItem(withTitle: NodiL("Вибрати все"), action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
        NSApp.mainMenu = menu
    }
    private func label(_ value: String, size: CGFloat = 13, bold: Bool = false) -> NSTextField {
        let view = NodiLabel(wrappingLabelWithString: value); view.font = .systemFont(ofSize: size, weight: bold ? .semibold : .regular); return view
    }
    private func row(_ views: [NSView]) -> NSStackView { let view = NSStackView(views: views); view.orientation = .horizontal; view.spacing = 10; view.alignment = .centerY; return view }
    private func card(_ views: [NSView]) -> NSView {
        let root = CardView(); root.wantsLayer = true
        let stack = NSStackView(views: views); stack.orientation = .vertical; stack.alignment = .leading; stack.spacing = 12
        stack.translatesAutoresizingMaskIntoConstraints = false; root.addSubview(stack)
        NSLayoutConstraint.activate([stack.topAnchor.constraint(equalTo: root.topAnchor, constant: 18), stack.bottomAnchor.constraint(equalTo: root.bottomAnchor, constant: -18), stack.leadingAnchor.constraint(equalTo: root.leadingAnchor, constant: 18), stack.trailingAnchor.constraint(equalTo: root.trailingAnchor, constant: -18)])
        for v in views { v.widthAnchor.constraint(equalTo: stack.widthAnchor).isActive = true }; return root
    }
    private func wire(_ button: NSButton, _ action: Selector) { button.target = self; button.action = action; button.bezelStyle = .rounded }
    private func buildWindow() {
        self.window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 820, height: 640), styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
        self.window.appearance = NSAppearance(named: .darkAqua)
        self.window.backgroundColor = NSColor(srgbRed: 16/255, green: 18/255, blue: 23/255, alpha: 1)
        self.window.title = "Nodi"; self.window.contentMinSize = NSSize(width: 760, height: 600); self.window.isReleasedWhenClosed = false; self.window.center()
        let scroll = NSScrollView(); scroll.drawsBackground = false; scroll.hasVerticalScroller = true; self.window.contentView = scroll
        let root = FlippedDocumentView(); root.wantsLayer=true; root.layer?.backgroundColor=self.window.backgroundColor.cgColor; scroll.documentView = root; root.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([root.widthAnchor.constraint(equalTo: scroll.contentView.widthAnchor), root.leadingAnchor.constraint(equalTo: scroll.contentView.leadingAnchor), root.topAnchor.constraint(equalTo: scroll.contentView.topAnchor)])
        self.wire(self.receiveButton, #selector(toggleReceive)); self.wire(self.folderButton, #selector(chooseFolder)); self.wire(self.addButton, #selector(showQR)); self.wire(self.forgetButton, #selector(forgetDevice)); self.wire(self.chooseButton, #selector(chooseFile)); self.wire(self.sendButton, #selector(sendFile)); self.wire(self.cancelButton, #selector(cancelSend))
        self.devicePicker.target = self; self.devicePicker.action = #selector(deviceChanged)
        self.addressPicker.target = self; self.addressPicker.action = #selector(addressChanged)
        let refresh = NodiButton(title: "Знайти у Wi-Fi", target: self, action: #selector(refreshDevices)); refresh.bezelStyle = .rounded
        let open = NodiButton(title: "Відкрити", target: self, action: #selector(openInbox)); open.bezelStyle = .rounded
        self.folderLabel.stringValue = self.inbox.lastPathComponent; self.folderLabel.toolTip = self.inbox.path; self.folderLabel.lineBreakMode = .byTruncatingMiddle
        self.fileLabel.lineBreakMode = .byTruncatingMiddle
        self.spinner.style = .spinning; self.spinner.controlSize = .small; self.spinner.isDisplayedWhenStopped = false
        let title = self.label("nodi", size: 26, bold: true)
        let mark=NSImageView();mark.image=NSImage(contentsOfFile:Bundle.main.path(forResource:"NodiMark",ofType:"png") ?? "");mark.imageScaling = .scaleProportionallyUpOrDown
        mark.widthAnchor.constraint(equalToConstant:40).isActive=true;mark.heightAnchor.constraint(equalToConstant:40).isActive=true
        self.addButton.title = "Додати телефон"; self.forgetButton.title = "Видалити"
        self.chooseButton.title = "Вибрати файл"; self.sendButton.title = "Надіслати"; self.folderButton.title = "Змінити"
        let accent = NSColor(srgbRed: 160/255, green: 177/255, blue: 255/255, alpha: 1)
        for button in [refresh, self.sendButton] { button.primary = true; button.bezelColor = accent; button.contentTintColor = .black; button.font = .systemFont(ofSize: 14, weight: .semibold); button.heightAnchor.constraint(equalToConstant: 40).isActive = true }
        self.symbol(refresh,"wifi"); self.symbol(self.addButton,"qrcode"); self.symbol(self.forgetButton,"trash")
        self.symbol(self.chooseButton,"doc.badge.plus"); self.symbol(self.sendButton,"arrow.up"); self.symbol(open,"folder")
        self.forgetButton.contentTintColor = .secondaryLabelColor
        self.deviceHint.font = .systemFont(ofSize: 12); self.deviceHint.textColor = .secondaryLabelColor
        self.sendStatus.font = .systemFont(ofSize: 13); self.sendStatus.textColor = .secondaryLabelColor
        self.fileLabel.font = .systemFont(ofSize: 15, weight: .medium)
        self.devicePicker.controlSize = .large; self.devicePicker.heightAnchor.constraint(equalToConstant:38).isActive=true
        let connection = self.card([self.row([self.label("Пристрої", size: 15, bold: true), NSView(), refresh]), self.devicePicker, self.row([self.addButton, NSView(), self.forgetButton]), self.deviceHint])
        let network = NodiButton(title: "Мережа…", target: self, action: #selector(showNetwork)); network.bezelStyle = .rounded
        let receive = self.card([self.label("Отримати", size: 15, bold: true), self.receiveStatus, self.receiveButton, self.folderLabel, self.row([self.folderButton,open,network])])
        self.receiveStatus.maximumNumberOfLines=2
        self.receiveStatus.font = .systemFont(ofSize:12); self.receiveStatus.textColor = .secondaryLabelColor
        self.folderLabel.font = .systemFont(ofSize:12); self.folderLabel.textColor = .secondaryLabelColor
        let send = self.card([self.label("Надіслати", size: 15, bold: true), self.fileLabel, self.chooseButton, self.row([self.sendButton, self.cancelButton, self.spinner]), self.sendStatus])
        let logScroll = NSScrollView(); logScroll.hasVerticalScroller = true; logScroll.borderType = .bezelBorder; logScroll.heightAnchor.constraint(equalToConstant: 90).isActive = true; logScroll.isHidden = true; self.logPanel = logScroll
        self.activity.isEditable = false; self.activity.isVerticallyResizable = true; self.activity.autoresizingMask = [.width]; self.activity.textContainer?.widthTracksTextView = true; self.activity.textContainerInset = NSSize(width: 8, height: 8); logScroll.documentView = self.activity
        let details = NodiButton(title: "Події", target: self, action: #selector(toggleLogs)); details.bezelStyle = .rounded
        self.lastEvent.font = .systemFont(ofSize: 11); self.lastEvent.textColor = .secondaryLabelColor; self.lastEvent.lineBreakMode = .byTruncatingTail
        let settingsButton=NodiButton(title:"Параметри",target:self,action:#selector(settings));settingsButton.bezelStyle = .rounded
        self.symbol(settingsButton,"gearshape");settingsButton.toolTip=NodiL("Параметри");settingsButton.setAccessibilityLabel(NodiL("Параметри"));settingsButton.title=""
        settingsButton.widthAnchor.constraint(equalToConstant:36).isActive=true
        let header=self.row([mark,title,NSView(),settingsButton])
        let transfers=NSStackView(views:[send,receive]);transfers.orientation = .horizontal;transfers.alignment = .top;transfers.distribution = .fillEqually;transfers.spacing=16
        send.heightAnchor.constraint(equalTo:receive.heightAnchor).isActive=true
        let stack = NSStackView(views: [header, self.label("Файли поруч", size: 13), connection, transfers, self.row([details, self.lastEvent]), logScroll])
        stack.orientation = .vertical; stack.alignment = .leading; stack.spacing = 12; stack.translatesAutoresizingMaskIntoConstraints = false; root.addSubview(stack)
        NSLayoutConstraint.activate([stack.leadingAnchor.constraint(equalTo: root.leadingAnchor, constant: 24), stack.trailingAnchor.constraint(equalTo: root.trailingAnchor, constant: -24), stack.topAnchor.constraint(equalTo: root.topAnchor, constant: 20), stack.bottomAnchor.constraint(equalTo: root.bottomAnchor, constant: -20)])
        for v in [header, connection, transfers, logScroll] { v.widthAnchor.constraint(equalTo: stack.widthAnchor).isActive = true }
        self.renderDevices(); self.setBusy(false); self.window.makeKeyAndOrderFront(nil)
    }
    private var previewWindow: NSPanel?
    private var previewURL: URL?
    private var previewTimer: Timer?
    @objc private func settings() { self.showSetup(first: false) }
    private func showSetup(first: Bool) {
        if self.sender != nil { return }
        let setup=NodiSetup(first:first)
        setup.present(parent:self.window)
        if let root=self.window?.contentView { self.translateUI(root);self.makeMenu();self.renderDevices() }
    }
    private func translateUI(_ view:NSView) {
        if let field=view as? NSTextField { field.stringValue=NodiL(field.stringValue) }
        if let button=view as? NSButton { button.title=NodiL(button.title) }
        view.subviews.forEach { self.translateUI($0) }
    }
    private func pulse(_ view:NSView, enabled:Bool) {
        view.wantsLayer=true;view.layer?.removeAnimation(forKey:"nodi.transfer")
        guard enabled && !self.preferences.bool(forKey:"nodi.reduceMotion") else { return }
        let animation=CABasicAnimation(keyPath:"opacity");animation.fromValue=1;animation.toValue=0.45;animation.duration=0.65;animation.autoreverses=true;animation.repeatCount = .infinity
        view.layer?.add(animation,forKey:"nodi.transfer")
    }
    private func showReceived(_ url:URL) {
        if self.preferences.object(forKey:"nodi.previews") != nil && !self.preferences.bool(forKey:"nodi.previews") { return }
        self.dismissPreview();self.previewURL=url
        let panel=NSPanel(contentRect:NSRect(x:0,y:0,width:400,height:160),styleMask:[.nonactivatingPanel],backing:.buffered,defer:false)
        panel.appearance=NSAppearance(named:.darkAqua);panel.isReleasedWhenClosed=false;panel.isOpaque=false;panel.backgroundColor = .clear;panel.hasShadow=true
        let root=CardView();root.wantsLayer=true;panel.contentView=root
        let thumbnail=NSImageView();thumbnail.image=NSWorkspace.shared.icon(forFile:url.path);thumbnail.imageScaling = .scaleProportionallyUpOrDown
        thumbnail.widthAnchor.constraint(equalToConstant:64).isActive=true;thumbnail.heightAnchor.constraint(equalToConstant:64).isActive=true
        let name=self.label(url.lastPathComponent,size:15,bold:true);name.maximumNumberOfLines=2;name.lineBreakMode = .byTruncatingMiddle
        let labels=NSStackView(views:[self.label(NodiL("Отримано"),size:12),name]);labels.orientation = .vertical;labels.alignment = .leading;labels.spacing=6
        let open=NodiButton(title:"Відкрити",target:self,action:#selector(openPreview));open.bezelStyle = .rounded
        let close=NodiButton(title:"Закрити",target:self,action:#selector(dismissPreview));close.bezelStyle = .rounded
        let content=NSStackView(views:[self.row([thumbnail,labels]),self.row([open,close])]);content.orientation = .vertical;content.alignment = .leading;content.spacing=16;content.translatesAutoresizingMaskIntoConstraints=false;root.addSubview(content)
        NSLayoutConstraint.activate([content.leadingAnchor.constraint(equalTo:root.leadingAnchor,constant:20),content.trailingAnchor.constraint(equalTo:root.trailingAnchor,constant:-20),content.topAnchor.constraint(equalTo:root.topAnchor,constant:18)])
        let destination=NSRect(x:self.window.frame.maxX-420,y:self.window.frame.minY+22,width:400,height:160)
        panel.setFrame(destination.offsetBy(dx:0,dy:-30),display:false);self.window.addChildWindow(panel,ordered:.above);self.previewWindow=panel;panel.orderFront(nil)
        if !self.preferences.bool(forKey:"nodi.reduceMotion") { panel.alphaValue=0;NSAnimationContext.runAnimationGroup { context in context.duration=0.25;panel.animator().alphaValue=1;panel.animator().setFrame(destination,display:true) } }else{panel.setFrame(destination,display:true)}
        self.previewTimer=Timer.scheduledTimer(withTimeInterval:15,repeats:false) { [weak self] _ in self?.dismissPreview() }
        DispatchQueue.global(qos:.userInitiated).async { [weak self, weak panel, weak thumbnail] in
            guard let source=CGImageSourceCreateWithURL(url as CFURL,nil),let cg=CGImageSourceCreateThumbnailAtIndex(source,0,[kCGImageSourceCreateThumbnailFromImageAlways:true,kCGImageSourceCreateThumbnailWithTransform:true,kCGImageSourceThumbnailMaxPixelSize:192] as CFDictionary) else { return }
            DispatchQueue.main.async { guard let self=self,let panel=panel,self.previewWindow === panel else { return };thumbnail?.image=NSImage(cgImage:cg,size:NSSize(width:CGFloat(cg.width),height:CGFloat(cg.height))) }
        }
    }
    @objc private func openPreview() { if let url=self.previewURL { NSWorkspace.shared.open(url) } }
    @objc private func dismissPreview() { self.previewTimer?.invalidate();self.previewTimer=nil;if let panel=self.previewWindow { self.window.removeChildWindow(panel);panel.close() };self.previewWindow=nil;self.previewURL=nil }

    private func symbol(_ button: NSButton, _ name: String) {
        button.image = NSImage(systemSymbolName: name, accessibilityDescription: button.title)
        button.imagePosition = .imageLeading
    }
    @objc private func toggleLogs() { self.logPanel?.isHidden.toggle() }
    @objc private func showNetwork() {
        let alert = NSAlert(); alert.messageText = NodiL("Адреса для QR")
        self.addressPicker.frame = NSRect(x: 0,y: 0,width: 330,height: 32)
        alert.accessoryView = self.addressPicker; alert.addButton(withTitle: NodiL("Готово"))
        alert.beginSheetModal(for: self.window) { _ in }
    }
    private func log(_ text: String) {
        self.lastEvent.stringValue = text
        self.lastEvent.toolTip = text
        self.activity.textStorage?.append(NSAttributedString(string: text + "\n", attributes: [.font: NSFont.monospacedSystemFont(ofSize: 11, weight: .regular), .foregroundColor: NSColor.labelColor]))
        if self.activity.string.count > 20000 { self.activity.string = String(self.activity.string.suffix(12000)) }; self.activity.scrollToEndOfDocument(nil)
    }
    private func selectedDevice() -> SavedDevice? {
        guard let pin = self.devicePicker.selectedItem?.representedObject as? String else {
            return nil
        }
        return self.saved.first { $0.fingerprint == pin }
    }
    private func renderDevices() {
        let selectedPin = self.devicePicker.selectedItem?.representedObject as? String
        let menu = NSMenu()
        menu.autoenablesItems = false
        var added = Set<String>()

        func appendDevice(_ name: String, _ pin: String) {
            guard added.insert(pin).inserted else { return }
            let item = NSMenuItem(title: name, action: nil, keyEquivalent: "")
            item.representedObject = pin
            menu.addItem(item)
        }

        for peer in self.saved {
            let online = self.discovered.contains { $0.pin == peer.fingerprint }
            let status = NodiL(online ? "у мережі" : "не знайдено")
            appendDevice(peer.name + " · " + status, peer.fingerprint)
        }
        for peer in self.discovered {
            appendDevice(peer.name + " · " + NodiL("Потрібне підключення"), peer.pin)
        }
        if menu.items.isEmpty {
            menu.addItem(NSMenuItem(
                title: NodiL("Пристроїв ще немає"), action: nil, keyEquivalent: ""
            ))
        }

        self.devicePicker.menu = menu
        let selected = menu.items.first {
            ($0.representedObject as? String) == selectedPin && selectedPin != nil
        } ?? menu.items.first
        self.devicePicker.select(selected)
        self.deviceChanged()
    }
    @objc private func deviceChanged() {
        if let peer = self.selectedDevice() {
            self.deviceHint.stringValue = self.discovered.contains { $0.pin == peer.fingerprint } ? "Готовий до передачі" : "Офлайн · увімкни приймання на телефоні"
        } else { self.deviceHint.stringValue = "Скануй новий QR телефоном" };self.setBusy(self.sender != nil)
    }
    @objc private func refreshDevices() { self.browser.refresh() }
    private func updateInvites(_ values: [Invite]) {
        let previous = self.addressPicker.titleOfSelectedItem
        self.invites = values; self.addressPicker.removeAllItems()
        for invite in values { self.addressPicker.addItem(withTitle: invite.address) }
        if let previous = previous, values.contains(where: { $0.address == previous }) { self.addressPicker.selectItem(withTitle: previous) }
        else if let i = values.firstIndex(where: { !$0.address.hasPrefix("127.") }) { self.addressPicker.selectItem(at: i) }
    }
    @objc private func toggleReceive() {
        if let receiver = self.receiver {
            self.stopping = true; self.pendingQR = false; self.closeQR(); self.browser.advertise(nil)
            self.receiveButton.isEnabled = false; self.receiveStatus.stringValue = "Зупиняю…"; receiver.stop(); return
        }
        self.stopping = false; self.receiveButton.isEnabled = false; self.folderButton.isEnabled = false
        self.receiveStatus.stringValue = "Запускаю…"
        let task = CoreTask(); self.receiver = task
        do {
            try task.start(arguments: ["receive", "-json", "-listen", "0.0.0.0:0", "-out", self.inbox.path, "-devices", self.deviceDirectory.path], keepInput: true,
                onEvent: { [weak self] event in self?.receiveEvent(event) }, onExit: { [weak self] _ in
                    guard let self = self else { return }
                    self.closeQR(); self.browser.advertise(nil); self.receiver = nil; self.invites = []; self.stopping = false; self.pendingQR = false
                    self.receiveStatus.stringValue = "Приймання вимкнене"; self.receiveButton.title = "Увімкнути приймання"; self.receiveButton.isEnabled = true; self.folderButton.isEnabled = true; self.setBusy(self.sender != nil)
                })
        } catch { self.receiver = nil; self.receiveButton.isEnabled = true; self.folderButton.isEnabled = true; self.log(error.localizedDescription) }
    }
    private func receiveEvent(_ event: CoreEvent) {
        switch event.type {
        case "ready":
            self.updateInvites(event.invites ?? []); self.browser.advertise(self.invites.first)
            self.receiveStatus.stringValue = "Увімкнено · лише твої пристрої"; self.receiveButton.title = "Зупинити"; self.receiveButton.isEnabled = true
            self.setBusy(self.sender != nil)
            if self.pendingQR { self.receiver?.command("new_qr") }
        case "qr_ready": self.updateInvites(event.invites ?? []); self.pendingQR = false; self.displayQR()
        case "devices":
            let old = self.selectedDevice()?.fingerprint
            self.saved = event.devices ?? []
            if let pin = self.activeSendPin, !self.saved.contains(where: { $0.fingerprint == pin }) { self.cancelSend() }
            self.renderDevices()
            if let old = old, let i = self.saved.firstIndex(where: { $0.fingerprint == old }) { self.devicePicker.selectItem(at: i); self.deviceChanged() }
            self.closeQR()
        case "forgotten": self.log("Пристрій видалено. Його попередній ключ більше не дозволяє надсилати сюди файли.")
        case "received":
            if let path=event.path { self.showReceived(URL(fileURLWithPath:path)) }
            self.log("Збережено: \(URL(fileURLWithPath: event.path ?? "файл").lastPathComponent)")
        case "error", "fatal": self.log(event.message ?? "Помилка Nodi")
        default: break
        }
    }
    @objc private func showQR() {
        self.pendingQR = true
        if self.receiver == nil { self.toggleReceive() } else if !self.stopping && self.receiveButton.isEnabled { self.receiver?.command("new_qr") }
    }
    @objc private func addressChanged() { self.closeQR() }
    @objc private func closeQR() { self.qrTimer?.invalidate(); self.qrTimer = nil; if let panel = self.qrPanel { self.window.removeChildWindow(panel); panel.close() }; self.qrPanel = nil }
    private func displayQR() {
        let i = self.addressPicker.indexOfSelectedItem
        guard self.invites.indices.contains(i), !self.invites[i].address.hasPrefix("127."), let qr = InviteQRView(code: self.invites[i].code) else { self.log("Не знайдено адресу Wi-Fi для QR."); return }
        self.closeQR()
        let panel = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 510, height: 650), styleMask: [.titled, .closable], backing: .buffered, defer: false)
        panel.title = "Додати телефон"; panel.isReleasedWhenClosed = false
        let root = NSView(); panel.contentView = root
        let hint = self.label("На телефоні: Nodi → Додати пристрій")
        let note = self.label("Одноразовий · 2 хвилини · спільний Wi-Fi", size: 11)
        let done = NodiButton(title: "Закрити", target: self, action: #selector(closeQR)); done.bezelStyle = .rounded
        let stack = NSStackView(views: [self.label("Скануй телефоном", size: 20, bold: true), hint, qr, self.label(self.invites[i].address), note, done])
        stack.orientation = .vertical; stack.alignment = .centerX; stack.spacing = 12; stack.translatesAutoresizingMaskIntoConstraints = false; root.addSubview(stack)
        NSLayoutConstraint.activate([stack.leadingAnchor.constraint(equalTo: root.leadingAnchor, constant: 20), stack.trailingAnchor.constraint(equalTo: root.trailingAnchor, constant: -20), stack.topAnchor.constraint(equalTo: root.topAnchor, constant: 18), qr.widthAnchor.constraint(equalToConstant: 400), qr.heightAnchor.constraint(equalToConstant: 400), hint.widthAnchor.constraint(equalTo: stack.widthAnchor), note.widthAnchor.constraint(equalTo: stack.widthAnchor)])
        panel.center(); self.window.addChildWindow(panel, ordered: .above); self.qrPanel = panel; panel.makeKeyAndOrderFront(nil)
        self.qrTimer = Timer.scheduledTimer(withTimeInterval: 120, repeats: false) { [weak self] _ in self?.closeQR(); self?.log("QR прострочений. Натисни «Додати телефон — QR», щоб показати новий.") }
    }
    @objc private func forgetDevice() {
        guard let peer = self.selectedDevice(), self.receiver != nil else { return }
        let alert = NSAlert(); alert.messageText = NodiL("Видалити \(peer.name)?"); alert.informativeText = NodiL("Збережене підключення й доступ до цього Mac буде видалено. Активне отримання з цього пристрою перерветься. Для повторного підключення знадобиться новий QR. Wi-Fi пристрою залишиться увімкненим.")
        alert.addButton(withTitle: NodiL("Скасувати")); alert.addButton(withTitle: NodiL("Видалити"))
        alert.beginSheetModal(for: self.window) { [weak self] response in
            guard response == .alertSecondButtonReturn, let self = self else { return }
            if self.activeSendPin == peer.fingerprint { self.cancelSend() }
            self.receiver?.command("forget", pin: peer.fingerprint)
        }
    }
    @objc private func chooseFolder() {
        let picker = NSOpenPanel(); picker.canChooseFiles = false; picker.canChooseDirectories = true; picker.canCreateDirectories = true
        picker.beginSheetModal(for: self.window) { [weak self] response in
            guard response == .OK, let url = picker.url, let self = self else { return }
            self.inbox = url; self.preferences.set(url.path, forKey: "inbox"); self.folderLabel.stringValue = url.lastPathComponent; self.folderLabel.toolTip = url.path
        }
    }
    @objc private func openInbox() { do { try FileManager.default.createDirectory(at: self.inbox, withIntermediateDirectories: true); NSWorkspace.shared.open(self.inbox) } catch { self.log(error.localizedDescription) } }
    @objc private func chooseFile() {
        let picker=NSOpenPanel();picker.canChooseFiles=true;picker.canChooseDirectories=false;picker.allowsMultipleSelection=true
        picker.beginSheetModal(for:self.window){[weak self] response in
            guard response == .OK,let self=self else{return}
            self.selectedFiles=picker.urls
            self.fileLabel.stringValue=picker.urls.count==1 ? (picker.urls.first?.lastPathComponent ?? "") : NodiL("Файлів: ")+String(picker.urls.count)
            self.fileLabel.toolTip=picker.urls.map{$0.lastPathComponent}.joined(separator:"\n")
        }
    }
    private func setBusy(_ busy: Bool) {
        self.pulse(self.sendButton,enabled:busy)
        self.chooseButton.isEnabled = !busy; self.sendButton.isEnabled = !busy && self.selectedDevice() != nil; self.cancelButton.isEnabled = busy
        self.devicePicker.isEnabled = !busy && (!self.saved.isEmpty || !self.discovered.isEmpty)
        self.forgetButton.isEnabled = self.selectedDevice() != nil && self.receiver != nil && !self.stopping
        self.addButton.isEnabled = !self.stopping
        self.addressPicker.isEnabled = !self.invites.isEmpty && !self.stopping
        if busy { self.spinner.startAnimation(nil) } else { self.spinner.stopAnimation(nil) }
    }
    @objc private func sendFile() {
        guard self.sender==nil,let peer=self.selectedDevice() else{return}
        guard !self.selectedFiles.isEmpty else{self.chooseFile();return}
        let address=self.discovered.first(where:{$0.pin==peer.fingerprint})?.address ?? peer.address
        guard let code=peer.code(address:address) else{return}
        self.pendingFiles=self.selectedFiles;self.batchTotal=self.pendingFiles.count;self.batchSent=0
        self.sendCancelled=false;self.activeSendPin=peer.fingerprint
        self.sendNext(code:code)
    }
    private func finishBatch() {
        self.pendingFiles.removeAll();self.sender=nil;self.activeSendPin=nil;self.setBusy(false)
    }
    private func sendNext(code:String) {
        guard !self.sendCancelled,!self.pendingFiles.isEmpty else{self.finishBatch();return}
        let file=self.pendingFiles.removeFirst()
        self.sendConfirmed=false
        let task=CoreTask();self.sender=task;self.setBusy(true)
        self.sendStatus.stringValue="\(self.batchSent+1)/\(self.batchTotal) · \(file.lastPathComponent)"
        var failure:String?=nil
        do {
            try task.start(arguments:["send","-json","-code-stdin","-file",file.path],secret:code,onEvent:{[weak self] event in
                guard let self=self else{return}
                if event.type=="sent"{self.sendConfirmed=true}
                else if event.type=="error" || event.type=="fatal"{failure=event.message ?? NodiL("Помилка передачі")}
            },onExit:{[weak self] exitCode in
                guard let self=self else{return}
                self.sender=nil
                if self.sendConfirmed {self.batchSent+=1}
                if exitCode==0 && self.sendConfirmed && !self.sendCancelled && !self.pendingFiles.isEmpty {self.sendNext(code:code);return}
                let count=NodiL("Надіслано: ")+"\(self.batchSent)/\(self.batchTotal)"
                if self.sendCancelled {self.sendStatus.stringValue=NodiL("Передачу зупинено")+" · "+count}
                else if self.batchSent==self.batchTotal {self.sendStatus.stringValue=NodiL("Готово · файли збережено")+" · "+count}
                else {self.sendStatus.stringValue=(failure ?? NodiL("Не вдалося надіслати"))+" · "+count}
                self.finishBatch()
            })
        } catch {self.sendStatus.stringValue=error.localizedDescription+" · "+NodiL("Надіслано: ")+"\(self.batchSent)/\(self.batchTotal)";self.finishBatch()}
    }
    @objc private func cancelSend(){self.sendCancelled=true;self.pendingFiles.removeAll();self.sender?.stop()}

}

struct NearbyDevice {
    let name: String
    let address: String
    let pin: String
}

// mDNS carries only the public certificate fingerprint; never the upload token.
final class NearbyBrowser: NSObject, NetServiceBrowserDelegate, NetServiceDelegate {
    private let browser = NetServiceBrowser()
    private var services: [NetService] = []
    private var devices: [ObjectIdentifier: NearbyDevice] = [:]
    private var advertisement: NetService?
    var ownPin: String? { didSet { publishList() } }
    var onChange: (([NearbyDevice]) -> Void)?
    var onError: (() -> Void)?
    func start() {
        browser.delegate = self
        browser.searchForServices(ofType: "_nodi._tcp.", inDomain: "local.")
    }
    func refresh() { stopSearch(); start() }
    private func stopSearch() {
        browser.stop()
        services.forEach { $0.delegate = nil; $0.stop() }
        services.removeAll(); devices.removeAll(); publishList()
    }
    func stop() { stopSearch(); advertise(nil) }
    func advertise(_ invite: Invite?) {
        advertisement?.stop(); advertisement?.delegate = nil; advertisement = nil; ownPin = nil
        guard let invite = invite, let value = validInvite(invite.code),
              let portString = invite.address.split(separator: ":").last, let port = Int32(portString) else { return }
        let service = NetService(domain: "local.", type: "_nodi._tcp.", name: "Nodi " + String((Host.current().localizedName ?? "Mac").prefix(40)), port: port)
        ownPin = value.fingerprint
        service.setTXTRecord(NetService.data(fromTXTRecord: ["v": Data("1".utf8), "fp": Data(value.fingerprint.utf8)]))
        service.delegate = self
        advertisement = service
        service.publish()
    }
    private func publishList() {
        onChange?(devices.values.filter { $0.pin != ownPin }.sorted { $0.name < $1.name })
    }
    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        guard services.count < 32 else { return }
        services.append(service); service.delegate = self; service.resolve(withTimeout: 8)
    }
    func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService, moreComing: Bool) {
        let removed = services.filter { $0.name == service.name && $0.type == service.type && $0.domain == service.domain }
        for item in removed { item.stop(); item.delegate = nil; devices.removeValue(forKey: ObjectIdentifier(item)) }
        services.removeAll { item in removed.contains { $0 === item } }; publishList()
    }
    func netServiceBrowser(_ browser: NetServiceBrowser, didNotSearch errorDict: [String: NSNumber]) { onError?() }
    func netService(_ sender: NetService, didNotPublish errorDict: [String: NSNumber]) { onError?() }
    func netServiceDidResolveAddress(_ sender: NetService) {
        guard services.contains(where: { $0 === sender }), let txt = sender.txtRecordData() else { return }
        let values = NetService.dictionary(fromTXTRecord: txt)
        guard values["v"] == Data("1".utf8), let data = values["fp"], let pin = String(data: data, encoding: .utf8),
              isHex32(pin), sender.port > 0, sender.port <= 65535 else { return }
        for data in sender.addresses ?? [] {
            let ip: String? = data.withUnsafeBytes { raw in
                guard raw.count >= MemoryLayout<sockaddr_in>.size, let base = raw.baseAddress else { return nil }
                let address = base.assumingMemoryBound(to: sockaddr.self)
                guard address.pointee.sa_family == sa_family_t(AF_INET) else { return nil }
                var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                guard getnameinfo(address, socklen_t(data.count), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 else { return nil }
                return String(cString: host)
            }
            guard let ip = ip, !ip.hasPrefix("127."), ip != "0.0.0.0" else { continue }
            let name = String(sender.name.unicodeScalars.filter { !CharacterSet.controlCharacters.contains($0) }.map(String.init).joined().prefix(80))
            devices[ObjectIdentifier(sender)] = NearbyDevice(name: name, address: "\(ip):\(sender.port)", pin: pin)
            publishList(); break
        }
    }
}

struct InviteContents: Decodable {
    let address: String
    let token: String
    let fingerprint: String
}
func isHex32(_ value: String) -> Bool {
    value.utf8.count == 64 && value.utf8.allSatisfy { (48...57).contains($0) || (97...102).contains($0) }
}
func validInvite(_ code: String) -> InviteContents? {
    guard code.hasPrefix("nodi1-"), code.utf8.count <= 4096 else { return nil }
    var encoded = String(code.dropFirst(6)).replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
    encoded += String(repeating: "=", count: (4 - encoded.count % 4) % 4)
    guard let data = Data(base64Encoded: encoded), let value = try? JSONDecoder().decode(InviteContents.self, from: data),
          isHex32(value.token), isHex32(value.fingerprint),
          let last = value.address.lastIndex(of: ":"), let port = Int(value.address[value.address.index(after: last)...]), (1...65535).contains(port) else { return nil }
    let host = String(value.address[..<last])
    var addr = in_addr()
    guard inet_pton(AF_INET, host, &addr) == 1, host != "0.0.0.0" else { return nil }
    return value
}




func NodiL(_ original: String) -> String {
 let source=nodiWords.first(where: { $0.value.contains(original) })?.key ?? original
 let language=UserDefaults.standard.string(forKey:"nodi.language") ?? "en"
 if language == "uk" { return source }
 let index=language == "ru" ? 1 : 0
 if let value=nodiWords[source] { return value[index] }
 for (key,value) in nodiWords where (key.hasSuffix(":") || key.hasSuffix(" ")) && source.hasPrefix(key) { return value[index] + String(source.dropFirst(key.count)) }
 return source
}
private let nodiWords: [String:[String]] = [
"Додати пристрій": ["Add device", "Добавить устройство"],
"Потрібне підключення": ["Pair to connect", "Нужно подключение"],
"Скануй новий QR на Mac": ["Scan a new QR on your Mac", "Сканируй новый QR на Mac"],
"Скануй новий QR телефоном": ["Scan a new QR with your phone", "Сканируй новый QR телефоном"],
"На телефоні: Nodi → Додати пристрій": ["On your phone: Nodi → Add device", "На телефоне: Nodi → Добавить устройство"],
"Файлів: ": ["Files: ", "Файлов: "],
"Надіслано: ": ["Sent: ", "Отправлено: "],
"Готово · файли збережено": ["Done · files saved", "Готово · файлы сохранены"],
"Файли поруч":["Files nearby","Файлы рядом"],
"Знайти у Wi-Fi":["Find on Wi-Fi","Найти в Wi-Fi"],
"ПРИСТРОЇ":["DEVICES","УСТРОЙСТВА"],
"Пристрої":["Devices","Устройства"],
"Додай Mac через QR":["Add a Mac via QR","Добавь Mac через QR"],
"Додай телефон через QR":["Add a phone via QR","Добавь телефон через QR"],
"Додати Mac":["Add Mac","Добавить Mac"],
"Додати телефон":["Add phone","Добавить телефон"],
"Видалити пристрій":["Remove device","Удалить устройство"],
"Видалити":["Remove","Удалить"],
"Вибрати":["Select","Выбрать"],
"Надіслати":["Send","Отправить"],
"Отримати":["Receive","Получить"],
"ФАЙЛ":["FILE","ФАЙЛ"],
"Вибери файл для передачі":["Choose a file to send","Выбери файл для передачи"],
"Вибрати файл":["Choose file","Выбрать файл"],
"Вибери файл":["Choose a file","Выбери файл"],
"Файл не вибрано":["No file selected","Файл не выбран"],
"Скасувати":["Cancel","Отмена"],
"Готово до передачі":["Ready to send","Готово к отправке"],
"Готовий до передачі":["Ready to send","Готов к отправке"],
"Папка збереження":["Save folder","Папка сохранения"],
"Папку ще не вибрано":["No folder selected","Папка не выбрана"],
"Увімкнути приймання":["Start receiving","Включить приём"],
"Приймання вимкнене":["Receiving is off","Приём выключен"],
"Приймання зупинено":["Receiving stopped","Приём остановлен"],
"Зупинити":["Stop","Остановить"],
"Лише підключені пристрої · до 1 год":["Paired devices only · up to 1 hour","Только подключённые · до 1 часа"],
"Увімкнено · лише твої пристрої":["On · your devices only","Включено · только твои устройства"],
"Пристроїв ще немає":["No devices yet","Пока нет устройств"],
"у мережі":["online","в сети"],
"не знайдено":["offline","не найдено"],
"Офлайн · увімкни приймання на телефоні":["Offline · enable receiving on your phone","Офлайн · включи приём на телефоне"],
"Дозволь пошук у Wi-Fi":["Allow local network access","Разреши доступ к локальной сети"],
"Пошук недоступний · спробуй ще раз":["Search unavailable · try again","Поиск недоступен · попробуй снова"],
"Готово · файл збережено":["Done · file saved","Готово · файл сохранён"],
"Готово — файл на телефоні":["Received · file saved","Получено · файл сохранён"],
"Готово — файл на Mac":["Done · file on Mac","Готово · файл на Mac"],
"Отримую файл…":["Receiving…","Получение…"],
"Надсилаю файл…":["Sending…","Отправка…"],
"Перевіряю та зберігаю…":["Verifying and saving…","Проверка и сохранение…"],
"Готую файл…":["Preparing…","Подготовка…"],
"Готую файл і перевіряю цілісність…":["Preparing and verifying…","Подготовка и проверка…"],
"Чекаю підтвердження від Mac…":["Waiting for confirmation…","Ожидание подтверждения…"],
"Підключаюся до Mac…":["Connecting to Mac…","Подключение к Mac…"],
"Перевіряю сертифікат пристрою.":["Verifying device identity.","Проверка устройства."],
"Байти передано. Mac перевіряє та зберігає файл.":["Sent. Mac is verifying the file.","Отправлено. Mac проверяет файл."],
"Перевіряю папку…":["Checking folder…","Проверка папки…"],
"Читаю відомості про файл…":["Reading file details…","Чтение данных файла…"],
"Запускаю…":["Starting…","Запуск…"],
"Зупиняю…":["Stopping…","Остановка…"],
"Запускаю захищений приймач…":["Starting receiver…","Запуск приёмника…"],
"Очікую файли з Mac":["Ready to receive","Готов к приёму"],
"Відкриваю сканер…":["Opening scanner…","Открытие сканера…"],
"На Mac натисни «Додати телефон — QR».":["On Mac, choose Add phone.","На Mac нажми «Добавить телефон»."],
"Скануй телефоном":["Scan with your phone","Сканируй телефоном"],
"Одноразовий · 2 хвилини · спільний Wi-Fi":["One use · 2 minutes · same Wi-Fi","Одноразовый · 2 минуты · общая Wi-Fi"],
"Підключити телефон":["Add phone","Добавить телефон"],
"Закрити":["Close","Закрыть"],
"Готово":["Done","Готово"],
"Відкрити":["Open","Открыть"],
"Змінити":["Change","Изменить"],
"Мережа…":["Network…","Сеть…"],
"Адреса для QR":["QR address","Адрес для QR"],
"Події":["Activity","События"],
"Редагування":["Edit","Правка"],
"Копіювати":["Copy","Копировать"],
"Вставити":["Paste","Вставить"],
"Вибрати все":["Select all","Выбрать всё"],
"Завершити Nodi":["Quit Nodi","Завершить Nodi"],
"Залишитися":["Stay","Остаться"],
"Зупинити передачу й закрити Nodi?":["Stop the transfer and quit Nodi?","Остановить передачу и закрыть Nodi?"],
"Сканування скасовано":["Scan cancelled","Сканирование отменено"],
"Не вдалося відкрити сканер":["Scanner unavailable","Сканер недоступен"],
"Це не чинний код Nodi":["Invalid Nodi QR","Недействительный QR Nodi"],
"Зберігаю підключення в обидва боки…":["Pairing devices…","Подключение устройств…"],
"Mac підключений":["Mac connected","Mac подключён"],
"Файли в обидва боки":["Send and receive","Передача в обе стороны"],
"Не вдалося зберегти підключення":["Could not pair devices","Не удалось подключить устройства"],
"Не вдалося відкрити файл":["Could not open file","Не удалось открыть файл"],
"Не вдалося надіслати":["Could not send","Не удалось отправить"],
"Не вдалося отримати файл":["Could not receive","Не удалось получить"],
"Передачу зупинено":["Transfer stopped","Передача остановлена"],
"Пристрій видалено":["Device removed","Устройство удалено"],
"Перевір код з’єднання":["Check connection","Проверь подключение"],
"Потрібен доступ до локальної мережі":["Local network access required","Нужен доступ к локальной сети"],
"Спочатку вибери файл.":["Choose a file first.","Сначала выбери файл."],
"Спочатку вибери папку збереження.":["Choose a save folder first.","Сначала выбери папку сохранения."],
"Підключення видалене. Скануй QR Mac знову.":["Device removed. Scan a new Mac QR.","Устройство удалено. Сканируй новый QR Mac."],
"Доступ видалено на Mac. Видали підключення тут і скануй новий QR.":["Access revoked on Mac. Remove this pairing and scan a new QR.","Доступ на Mac отозван. Удали подключение и сканируй новый QR."],
"Збережено:":["Saved:","Сохранено:"],
"Папка:":["Folder:","Папка:"],
"Надсилання:":["Sending:","Отправка:"],
"Надсилаю ":["Sending ","Отправка "],
"Видалити ":["Remove ","Удалить "],
"Параметри":["Settings","Настройки"],
"Отримано":["Received","Получено"],
"Прев’ю файлів":["File previews","Превью файлов"],
"Менше анімацій":["Reduce motion","Меньше анимаций"],
"Мова":["Language","Язык"],
"Продовжити":["Continue","Продолжить"],
"Твої файли. Напряму.":["Your files. Directly.","Твои файлы. Напрямую."],
"Налаштуй Nodi під себе":["Make Nodi yours","Настрой Nodi под себя"],
"Збережене підключення й доступ до цього телефона буде видалено. Активна передача перерветься. Для повторного підключення скануй новий QR Mac. Пристрій залишиться у Wi-Fi.":["Remove this device’s access? Active transfers will stop. A new QR is needed to reconnect. Wi-Fi stays connected.","Удалить доступ устройства? Передача прервётся. Для подключения нужен новый QR. Wi-Fi останется подключённым."],
"Збережене підключення й доступ до цього Mac буде видалено. Активне отримання з цього пристрою перерветься. Для повторного підключення знадобиться новий QR. Wi-Fi пристрою залишиться увімкненим.":["Remove this device’s access? Active transfers will stop. A new QR is needed to reconnect. Wi-Fi stays connected.","Удалить доступ устройства? Передача прервётся. Для подключения нужен новый QR. Wi-Fi останется подключённым."],
"Для зв’язку в обидва боки спочатку вибери папку збереження, потім натисни «Додати Mac — сканувати QR».":["Choose a save folder to enable transfers both ways.","Выбери папку для передачи в обе стороны."],
"Запускаю приймання, щоб після сканування працювали обидва напрямки.":["Starting receiver for two-way transfers.","Запуск приёма для передачи в обе стороны."],
"Вибери файл і натисни «Надіслати файл».":["Choose a file and press Send.","Выбери файл и нажми «Отправить»."],
"Увімкни приймання та повтори сканування.":["Enable receiving and scan again.","Включи приём и сканируй снова."],
"На Mac покажи новий QR та повтори. Перевір спільний Wi-Fi і приймання на телефоні.":["Show a new Mac QR and retry. Check Wi-Fi and phone receiving.","Покажи новый QR на Mac. Проверь Wi-Fi и приём на телефоне."],
"На Mac натисни «Додати телефон — QR» і скануй новий код.":["On Mac choose Add phone, then scan the new QR.","На Mac нажми «Добавить телефон» и сканируй новый QR."],
"Натисни «Додати Mac — сканувати QR», щоб повторити.":["Tap Add Mac to try again.","Нажми «Добавить Mac» для повтора."],
"Дозволь Nodi доступ до локальної мережі в налаштуваннях телефона.":["Allow Nodi local network access in Settings.","Разреши Nodi доступ к локальной сети в настройках."],
"Дозволь доступ до локальної мережі або скористайся QR.":["Allow local network access.","Разреши доступ к локальной сети."],
"Дозволь доступ у налаштуваннях дозволів Nodi, щоб підключитися до Mac.":["Allow local network access in Nodi settings to connect.","Разреши локальную сеть в настройках Nodi."],
"Увімкни інтернет для першого завантаження сканера та перевір оновлення Google Play services. Потім повтори. Для першого підключення потрібен сканер Nodi.":["Connect to the internet to download the scanner. Update Google Play services and retry.","Подключи интернет для загрузки сканера. Обнови Google Play services и повтори."],
"Сканування скасовано. Натисни кнопку, щоб спробувати ще раз.":["Scan cancelled. Tap to retry.","Сканирование отменено. Нажми для повтора."],
"Підключення збережене":["Device paired","Подключение сохранено"],
"Спочатку скануй QR Mac":["Scan the Mac QR first","Сначала сканируй QR Mac"],
"Потрібен доступ на читання та запис у вибрану папку.":["Read and write access to this folder is required.","Нужен доступ на чтение и запись в папку."],
"Не вдалося отримати доступ до папки.":["Folder access failed.","Нет доступа к папке."],
"Ця папка недоступна для запису. Вибери іншу папку у внутрішній пам’яті.":["Choose a writable folder in internal storage.","Выбери доступную для записи папку во внутренней памяти."],
"Android не дозволив запуск. Залиш Nodi відкритим і натисни кнопку ще раз.":["Android blocked startup. Keep Nodi open and retry.","Android заблокировал запуск. Оставь Nodi открытым и повтори."],
"Приймач завершився. Увімкни його знову.":["Receiver stopped. Enable it again.","Приёмник остановлен. Включи его снова."],
"Зупиняю передачу…":["Stopping transfer…","Остановка передачи…"],
"Не вдалося видалити пристрій. Спробуй ще раз.":["Could not remove device. Try again.","Не удалось удалить устройство. Повтори."],
"Його старий ключ більше не дозволяє надсилати файли сюди.":["Its previous key no longer grants access.","Старый ключ больше не даёт доступ."],
"Не вдалося прочитати вибраний файл.":["Could not read the selected file.","Не удалось прочитать файл."],
"Невідома помилка.":["Unknown error.","Неизвестная ошибка."],
"На Mac уже є файл із таким ім’ям. Перейменуй файл або вибери інший.":["A file with this name already exists on Mac. Rename it or choose another.","На Mac уже есть файл с таким именем. Переименуй его или выбери другой."],
"На телефоні немає місця для тимчасової копії. Звільни місце або вибери менший файл.":["Not enough phone storage. Free up space or choose a smaller file.","На телефоне мало памяти. Освободи место или выбери файл меньше."],
"Mac відхилив ім’я або розмір файлу. Максимум — 2 GiB.":["Mac rejected the name or size. Maximum: 2 GiB.","Mac отклонил имя или размер. Максимум — 2 GiB."],
"Mac недоступний. Перевір спільний Wi-Fi, мережеву адресу й дозвіл фаєрвола для Nodi.":["Mac is unavailable. Check Wi-Fi and Nodi firewall permissions.","Mac недоступен. Проверь Wi-Fi и разрешения брандмауэра."],
"Mac не приймає з’єднання. Увімкни приймання й скопіюй новий код.":["Mac is not receiving. Open Nodi on Mac.","Mac не принимает файлы. Открой Nodi на Mac."],
"Mac відхилив код. Скопіюй актуальний код із вікна Nodi.":["Access rejected. Pair again using a new QR.","Доступ отклонён. Подключись через новый QR."],
"Не вдалося перевірити Mac. Перезапусти приймання й скопіюй новий код.":["Mac identity verification failed. Pair again using a new QR.","Не удалось проверить Mac. Подключись через новый QR."],
"Перевірка цілісності не пройшла. Mac відкинув файл.":["Integrity check failed. Mac discarded the file.","Проверка целостности не пройдена. Mac отклонил файл."],
"Якщо файл устиг надійти, він залишиться на Mac. Перевір папку перед повтором.":["A completed file may remain on Mac. Check the folder before retrying.","Если файл уже получен, он останется на Mac. Проверь папку перед повтором."],
"Потрібен Android 10 або новіший для TLS 1.3.":["Android 10 or later is required.","Нужен Android 10 или новее."],
"Це код для тесту на Mac":["This QR is for a Mac-only test","Этот QR для теста на самом Mac"],
"Вибери на Mac адресу Wi-Fi та покажи її QR-код.":["Select the Mac Wi-Fi address and show its QR.","Выбери адрес Wi-Fi на Mac и покажи QR."],
"Підключення видалене на телефоні. Видали його тут і скануй новий QR.":["Pairing removed on the phone. Remove it here and scan a new QR.","Подключение удалено на телефоне. Удали его здесь и сканируй новый QR."],
"Передачу зупинено. Якщо файл уже збережений, він залишиться на телефоні.":["Transfer stopped. Completed files remain on the phone.","Передача остановлена. Сохранённые файлы останутся на телефоне."],
"Перевір Wi-Fi та чи ввімкнене приймання на телефоні.":["Check Wi-Fi and enable receiving on the phone.","Проверь Wi-Fi и включи приём на телефоне."],
"Пошук недоступний. Перевір дозвіл локальної мережі та Wi-Fi.":["Search unavailable. Check local network permission and Wi-Fi.","Поиск недоступен. Проверь разрешение локальной сети и Wi-Fi."],
"Не знайдено адресу Wi-Fi для QR.":["No Wi-Fi address found for QR.","Не найден адрес Wi-Fi для QR."],
"Пристрій видалено. Його попередній ключ більше не дозволяє надсилати сюди файли.":["Device removed. Its previous key no longer grants access.","Устройство удалено. Старый ключ больше не даёт доступ."],
"QR прострочений. Натисни «Додати телефон — QR», щоб показати новий.":["QR expired. Choose Add phone for a new one.","QR истёк. Нажми «Добавить телефон» для нового."],
"Зупиняю приймання…":["Stopping receiver…","Остановка приёма…"],
"Nodi — приймання файлів":["Nodi — receiving","Nodi — приём файлов"],
"Приймання файлів Nodi":["Nodi file receiving","Приём файлов Nodi"],
"Приймання увімкнене.":["Receiving enabled.","Приём включён."]
]

final class NodiLabel:NSTextField { override var stringValue:String { get { super.stringValue } set { super.stringValue=NodiL(newValue) } } }
final class NodiButton:NSButton {
    var primary=false { didSet { needsDisplay=true } }
    override var title:String { get { super.title } set { super.title=NodiL(newValue);invalidateIntrinsicContentSize();needsDisplay=true } }
    override var intrinsicContentSize:NSSize {
        let width=(self.title as NSString).size(withAttributes:[.font:NSFont.systemFont(ofSize:13,weight:.medium)]).width
        return NSSize(width:max(36,width+28+(self.image == nil ? 0 : (self.title.isEmpty ? 0 : 22))),height:36)
    }
    override func draw(_ dirtyRect:NSRect) {
        let accent=NSColor(srgbRed:160/255,green:177/255,blue:1,alpha:1)
        let surface=NSColor(srgbRed:38/255,green:43/255,blue:53/255,alpha:1)
        let alpha:CGFloat=self.isEnabled ? 1 : 0.38
        let fill=self.primary ? accent : surface
        fill.withAlphaComponent(self.isHighlighted ? 0.65 : alpha).setFill()
        NSBezierPath(roundedRect:self.bounds.insetBy(dx:1,dy:1),xRadius:10,yRadius:10).fill()
        let color=(self.primary ? NSColor(srgbRed:16/255,green:18/255,blue:23/255,alpha:1) : NSColor(srgbRed:231/255,green:235/255,blue:244/255,alpha:1)).withAlphaComponent(alpha)
        let attrs:[NSAttributedString.Key:Any]=[.font:NSFont.systemFont(ofSize:13,weight:.medium),.foregroundColor:color]
        let size=(self.title as NSString).size(withAttributes:attrs)
        let imageWidth:CGFloat=self.image == nil ? 0 : (self.title.isEmpty ? 16 : 22)
        let x=max(10,(self.bounds.width-size.width-imageWidth)/2)
        if let original=self.image, let icon=original.copy() as? NSImage {
            icon.isTemplate=false
            icon.lockFocus();color.set();NSRect(origin:.zero,size:icon.size).fill(using:.sourceAtop);icon.unlockFocus()
            icon.draw(in:NSRect(x:self.title.isEmpty ? (self.bounds.width-16)/2 : x,y:(self.bounds.height-16)/2,width:16,height:16))
        }
        (self.title as NSString).draw(at:NSPoint(x:x+imageWidth,y:(self.bounds.height-size.height)/2),withAttributes:attrs)
        if self.window?.firstResponder === self {
            accent.withAlphaComponent(0.8).setStroke();let ring=NSBezierPath(roundedRect:self.bounds.insetBy(dx:2,dy:2),xRadius:9,yRadius:9);ring.lineWidth=2;ring.stroke()
        }
    }
}


let app = NSApplication.shared
let delegate = AppDelegate()
app.setActivationPolicy(.regular)
app.delegate = delegate
app.run()
