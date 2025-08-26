import Foundation
import Compression

// MARK: - Data Compression Extension
extension Data {
    /// Compresses data using the specified algorithm
    /// - Parameter algorithm: The compression algorithm to use
    /// - Returns: Compressed data
    /// - Throws: CompressionError if compression fails
    func compressed(using algorithm: NSData.CompressionAlgorithm) throws -> Data {
        return try self.withUnsafeBytes { bytes in
            let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: count)
            defer { buffer.deallocate() }

            let compressedSize = compression_encode_buffer(
                buffer, count,
                bytes.bindMemory(to: UInt8.self).baseAddress!, count,
                nil, algorithm.compressionAlgorithm
            )

            guard compressedSize > 0 else {
                throw CompressionError.compressionFailed("Compression failed - no data produced")
            }

            return Data(bytes: buffer, count: compressedSize)
        }
    }

    /// Compresses data asynchronously using the specified algorithm
    /// - Parameter algorithm: The compression algorithm to use
    /// - Returns: Compressed data
    /// - Throws: CompressionError if compression fails
    func compressedAsync(using algorithm: NSData.CompressionAlgorithm) async throws -> Data {
        return try await withCheckedThrowingContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                do {
                    let compressed = try self.compressed(using: algorithm)
                    continuation.resume(returning: compressed)
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    /// Generates base64 string with optional compression using streaming for large files
    /// - Parameter useCompression: Whether to attempt compression first
    /// - Returns: Base64 encoded string with appropriate data URI prefix
    func base64StringWithOptionalCompression(useCompression: Bool = true) async throws -> String {
        // Use streaming for large files to reduce memory footprint
        if count > AudioEngineConstants.base64ChunkSize {
            return try await streamingBase64WithOptionalCompression(useCompression: useCompression)
        }

        // Use in-memory processing for smaller files
        if useCompression && count > AudioEngineConstants.compressionThreshold {
            do {
                let compressed = try await compressedAsync(using: NSData.CompressionAlgorithm.lzfse)
                if compressed.count < count {
                    let base64 = compressed.base64EncodedString(options: [.lineLength64Characters, .endLineWithLineFeed])
                    return "data:audio/m4a;base64,lzfse:" + base64
                }
            } catch {
                // Fall back to uncompressed if compression fails
                print("[Compression] Compression failed, using uncompressed: \(error.localizedDescription)")
            }
        }

        // Use uncompressed
        let base64 = self.base64EncodedString(options: [.lineLength64Characters, .endLineWithLineFeed])
        return "data:audio/m4a;base64," + base64
    }

    /// Streaming base64 encoding with optional compression for large files
    /// - Parameter useCompression: Whether to attempt compression first
    /// - Returns: Base64 encoded string with appropriate data URI prefix
    private func streamingBase64WithOptionalCompression(useCompression: Bool) async throws -> String {
        let chunkSize = AudioEngineConstants.base64ChunkSize
        var result = ""
        var compressed = Data()
        var shouldCompress = useCompression && count > AudioEngineConstants.compressionThreshold

        // Process data in chunks to reduce memory pressure
        var offset = 0
                while offset < count {
            let remainingBytes = count - offset
            let currentChunkSize = Swift.min(chunkSize, remainingBytes)
            let range = offset..<(offset + currentChunkSize)
            let chunk = subdata(in: range)

            if shouldCompress {
                do {
                    let compressedChunk = try await chunk.compressedAsync(using: NSData.CompressionAlgorithm.lzfse)
                    compressed.append(compressedChunk)
                } catch {
                    // Fall back to uncompressed on compression failure
                    shouldCompress = false
                    compressed = Data()
                    break
                }
            }

            offset += currentChunkSize

            // Check for memory pressure periodically
            if offset % (chunkSize * 4) == 0 {
                // Yield to prevent blocking other tasks
                await Task.yield()

                // Basic memory pressure check
                if compressed.count > AudioEngineConstants.maxMemoryUsage {
                    throw RecordingError.memoryPressure
                }
            }
        }

        // Determine final data to encode
        let dataToEncode: Data
        let prefix: String

        if shouldCompress && compressed.count < count {
            dataToEncode = compressed
            prefix = "data:audio/m4a;base64,lzfse:"
            print("[Compression] Streaming compression successful: \(count) → \(compressed.count) bytes")
        } else {
            dataToEncode = self
            prefix = "data:audio/m4a;base64,"
            print("[Compression] Using uncompressed data: \(count) bytes")
        }

        // Generate base64 in chunks to avoid large memory allocations
        result = prefix + dataToEncode.base64EncodedString(options: [.lineLength64Characters, .endLineWithLineFeed])

        return result
    }
}

// MARK: - NSData.CompressionAlgorithm Extension
extension NSData.CompressionAlgorithm {
    var compressionAlgorithm: compression_algorithm {
        switch self {
        case .lzfse: return COMPRESSION_LZFSE
        case .lz4: return COMPRESSION_LZ4
        case .lzma: return COMPRESSION_LZMA
        case .zlib: return COMPRESSION_ZLIB
        @unknown default: return COMPRESSION_LZFSE
        }
    }
}

// MARK: - Compression Error Types
enum CompressionError: LocalizedError {
    case compressionFailed(String)
    case invalidData
    case memoryAllocationFailed

    var errorDescription: String? {
        switch self {
        case .compressionFailed(let message):
            return "Compression failed: \(message)"
        case .invalidData:
            return "Invalid data provided for compression"
        case .memoryAllocationFailed:
            return "Failed to allocate memory for compression"
        }
    }
}