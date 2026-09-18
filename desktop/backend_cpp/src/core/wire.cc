// ============================================================================
//  MicRouter C++ backend — socket helpers (big-endian wire protocol).
//
//  The Android app (AudioService.kt) speaks this exact protocol:
//    * 4-byte big-endian sample rate
//    * 4-byte big-endian ack (echo of sample rate)
//    * 4-byte big-endian "REDY" (0x52454459)
//    * framed audio: 4-byte big-endian length + that many raw int16 bytes
// ============================================================================
#include "core/backend.h"

#include <cerrno>

#if defined(_WIN32)
#  define WIN32_LEAN_AND_MEAN
#  include <winsock2.h>
#  include <ws2tcpip.h>
#else
#  include <arpa/inet.h>
#  include <fcntl.h>
#  include <netinet/in.h>
#  include <netinet/tcp.h>
#  include <sys/socket.h>
#  include <unistd.h>
#endif

namespace mr {

namespace {
int SocketError() {
#if defined(_WIN32)
  return WSAGetLastError();
#else
  return errno;
#endif
}
}  // namespace

ReadStatus ReadExactly(int fd, uint8_t* dst, size_t n) {
  size_t got = 0;
  while (got < n) {
    // WinSock recv() returns int (not ssize_t); int is safe on both platforms
    // because individual reads are bounded by the chunk size (<= 64 KiB).
    int r = recv(fd, reinterpret_cast<char*>(dst + got),
                 static_cast<int>(n - got), 0);
    if (r > 0) {
      got += static_cast<size_t>(r);
      continue;
    }
    if (r == 0) return ReadStatus::kClosed;  // peer closed
    int e = SocketError();
#if defined(_WIN32)
    if (e == WSAETIMEDOUT || e == WSAEWOULDBLOCK) return ReadStatus::kTimeout;
#else
    if (e == EAGAIN || e == EWOULDBLOCK) return ReadStatus::kTimeout;
#endif
    return ReadStatus::kTimeout;
  }
  return ReadStatus::kOk;
}

ReadStatus ReadInt32BE(int fd, int32_t* out) {
  uint8_t b[4];
  ReadStatus st = ReadExactly(fd, b, 4);
  if (st != ReadStatus::kOk) return st;
  *out = (static_cast<int32_t>(b[0]) << 24) |
         (static_cast<int32_t>(b[1]) << 16) |
         (static_cast<int32_t>(b[2]) << 8) | static_cast<int32_t>(b[3]);
  return ReadStatus::kOk;
}

bool WriteInt32BE(int fd, int32_t value) {
  uint8_t b[4];
  b[0] = static_cast<uint8_t>((static_cast<uint32_t>(value) >> 24) & 0xff);
  b[1] = static_cast<uint8_t>((static_cast<uint32_t>(value) >> 16) & 0xff);
  b[2] = static_cast<uint8_t>((static_cast<uint32_t>(value) >> 8) & 0xff);
  b[3] = static_cast<uint8_t>(static_cast<uint32_t>(value) & 0xff);
  return WriteAll(fd, b, 4);
}

void SetReceiveTimeout(int fd, int seconds) {
#if defined(_WIN32)
  DWORD to = static_cast<DWORD>(seconds) * 1000;
  setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&to),
             sizeof(to));
#else
  struct timeval tv;
  tv.tv_sec = seconds;
  tv.tv_usec = 0;
  setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
#endif
}

void FlushSocket(int fd) {
  // Switch to non-blocking, drain everything pending, switch back.
#if defined(_WIN32)
  u_long non_blocking = 1;
  if (ioctlsocket(fd, FIONBIO, &non_blocking) != 0) return;
#else
  int flags = fcntl(fd, F_GETFL, 0);
  if (flags == -1) return;
  if (fcntl(fd, F_SETFL, flags | O_NONBLOCK) == -1) return;
#endif

  char tmp[4096];
  for (;;) {
    int r = recv(fd, tmp, sizeof(tmp), 0);
    if (r > 0) continue;
    break;  // 0 == closed, <0 == empty (or error): either way, stop draining.
  }

#if defined(_WIN32)
  u_long blocking = 0;
  ioctlsocket(fd, FIONBIO, &blocking);
#else
  fcntl(fd, F_SETFL, flags);
#endif
}

bool WriteAll(int fd, const void* data, size_t n) {
  const uint8_t* p = static_cast<const uint8_t*>(data);
  size_t sent = 0;
  while (sent < n) {
    int s = send(fd, reinterpret_cast<const char*>(p + sent),
                 static_cast<int>(n - sent), 0);
    if (s <= 0) {
      int e = SocketError();
#if defined(_WIN32)
      if (e == WSAEINTR) continue;
#else
      if (e == EINTR) continue;
#endif
      return false;
    }
    sent += static_cast<size_t>(s);
  }
  return true;
}

}  // namespace mr
