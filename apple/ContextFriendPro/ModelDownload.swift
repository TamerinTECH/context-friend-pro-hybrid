import Foundation

final class ModelDownload:NSObject, URLSessionDownloadDelegate {
    private let resumeFile:URL; private let report:(Double)->Void; private let lock = NSLock()
    private var session:URLSession?; private var task:URLSessionDownloadTask?; private var continuation:CheckedContinuation<URL,Error>?
    private var cancelled = false; private var saved:URL?
    init(resumeFile:URL,report:@escaping(Double)->Void) {self.resumeFile = resumeFile; self.report = report}
    func start(_ url:URL) async throws -> URL {
        try await withTaskCancellationHandler(operation:{ try await withCheckedThrowingContinuation { c in
            lock.lock(); defer {lock.unlock()}; if cancelled {c.resume(throwing:ProError.cancelled); return}; continuation = c
            let config = URLSessionConfiguration.ephemeral; config.urlCache = nil; config.httpCookieStorage = nil; config.requestCachePolicy = .reloadIgnoringLocalCacheData
            let session = URLSession(configuration:config,delegate:self,delegateQueue:nil); self.session = session
            if let data = try? Data(contentsOf:resumeFile) {task = session.downloadTask(withResumeData:data)} else {task = session.downloadTask(with:url)}
            task?.resume()
        } },onCancel:{self.cancel()})
    }
    private func cancel() {lock.lock(); cancelled = true; let task = task; lock.unlock()
        task?.cancel(byProducingResumeData:{ data in if let data {try? data.write(to:self.resumeFile,options:.atomic); try? excludeBackup(self.resumeFile)} })
    }
    func urlSession(_ session:URLSession,downloadTask:URLSessionDownloadTask,didWriteData bytesWritten:Int64,totalBytesWritten:Int64,totalBytesExpectedToWrite:Int64) {if totalBytesExpectedToWrite > 0 {report(Double(totalBytesWritten)/Double(totalBytesExpectedToWrite))}}
    func urlSession(_ session:URLSession,downloadTask:URLSessionDownloadTask,didFinishDownloadingTo location:URL) {
        guard let response = downloadTask.response as? HTTPURLResponse, response.statusCode == 200 || response.statusCode == 206 else {return}
        let destination = resumeFile.appendingPathExtension("download")
        do {if FileManager.default.fileExists(atPath:destination.path) {try FileManager.default.removeItem(at:destination)}; try FileManager.default.moveItem(at:location,to:destination); try excludeBackup(destination); saved = destination} catch {saved = nil}
    }
    func urlSession(_ session:URLSession,task:URLSessionTask,didCompleteWithError error:Error?) {
        lock.lock(); let c = continuation; continuation = nil; let wasCancelled = cancelled; lock.unlock()
        if let error = error as NSError?, let resume = error.userInfo[NSURLSessionDownloadTaskResumeData] as? Data {try? resume.write(to:resumeFile,options:.atomic); try? excludeBackup(resumeFile)}
        if !wasCancelled && error == nil, let saved {try? FileManager.default.removeItem(at:resumeFile); c?.resume(returning:saved)}
        else {if let saved {try? FileManager.default.removeItem(at:saved)}; c?.resume(throwing:wasCancelled ? ProError.cancelled : (error ?? ProError.unavailable))}
        session.finishTasksAndInvalidate(); self.session = nil; self.task = nil
    }
}
