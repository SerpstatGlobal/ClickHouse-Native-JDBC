# The case of the checksum that only failed after August 15

*How a seven-year-old bug in a JDBC driver hid behind the calendar, and how one
number in an error message gave it away.*

[Українська версія](STORY_uk.md)

## The symptom

We run a service that writes a lot into ClickHouse: batches of tens of
thousands of rows, several megabytes each, through
`housepower/ClickHouse-Native-JDBC 2.7.1`. In early September 2026, shortly
after moving the service to a new stack (Java 17, new cluster), the server began
to reject an INSERT every couple of days:

```
DB::Exception: Checksum doesn't match: corrupted data.
Reference: ad8c0b16bd6eefc04207d1e44223e3fd. Actual: 2a3ed20aa63b475f638293e914e94b64.
Size of compressed block: 18.
```

Our writer is fail-fast, so every event cost us a process restart and one lost
batch. Three events in five days across twelve instances. The same driver had
been running the same workload on the old stack (Java 8) for years, and that
exact error had **never** been seen there. Everything pointed at the new JVM.

## The wrong suspect

"Checksum doesn't match" is the kind of error people blame on the network or on
bad RAM. We ruled those out quickly: different hosts, clean kernel logs, a
single writer thread per connection.

Then we found a genuinely suspicious line in the driver,
`CompressedBuffedWriter.flushToTarget()`:

```java
byte[] compressedBuffer = new byte[maxLen + 25];
lz4Compressor.compress(writtenBuf, 0, position,
        compressedBuffer, /* offset */ 25, /* maxOutputLength */ compressedBuffer.length);
```

The compressor is told it has 25 more bytes than it really has after the
offset, and aircompressor 0.21 writes through `Unsafe` without bounds checks.
A beautiful theory wrote itself: *the compressor silently scribbles past the
array, Java 17 lays out the heap differently, so now the scribble lands on
something that matters.* There is even an open upstream issue (#469) about that
line.

The theory was wrong. A fuzz test with a guard region behind the declared bound
(16 392 rounds, incompressible and compressible inputs, 1 MB worst cases) showed
that `Lz4RawCompressor` never writes past `maxCompressedLength(n)`; the closest it
came was 14 bytes short. The overstated argument is a real mistake (aircompressor
0.27 rejects it with an exception, which is what #469 is about), but it does not
corrupt memory.

## The clue

Our working rule is: no fix until the bug reproduces on demand. So we went back
to the error message and, this time, read it to the end:

> **Size of compressed block: 18.**

Eighteen bytes. That is a 9-byte compression header plus 9 bytes of LZ4 payload,
which is 8 bytes of raw data. Not a megabyte-sized block near a buffer boundary,
but a tiny one. The driver compresses a `Data` packet in 1 MB frames and
force-flushes whatever is left as one last small frame. The server-side stack
trace showed it was reading the last column of the block when it choked. So the
broken piece was the *tail frame*, and the question became: what is special
about a tiny frame for the checksum?

## The mechanism

ClickHouse protects every compressed block with CityHash128 (version 1.0.2, the
one in `contrib/cityhash102` of the ClickHouse repository). The driver ports it
to Java in `ClickHouseCityHash`. The branch for a 1..3-byte tail reads:

```java
byte a = s[pos];
byte b = s[pos + (len >>> 1)];
byte c = s[pos + len - 1];
int y = (int) a + (((int) b) << 8);
int z = len + (((int) c) << 2);
return shiftMix(y * k2 ^ z * k3) * k2;
```

In the C++ original `a`, `b`, `c` are `uint8` and `y`, `z` are `uint32`. In Java
`byte` is signed: any byte with the high bit set (≥ 0x80) becomes negative when
widened to `int`, the sign leaks into `y`/`z` and then into the 64-bit
multiplication, and the hash comes out different. The server computes the right
value, the client sends the wrong one, the server says "corrupted data".

Why so rare? The first 16 bytes of the hashed data become the seed; only bytes
17..19 ever reach this branch. So the hash can only break for a compressed frame
of **17, 18 or 19 bytes**, i.e. an LZ4 payload of 8..10 bytes, i.e. a `Data`
packet whose remainder after the 1 MB frames is 7..9 bytes. Roughly 3 out of
every 1 048 576 INSERTs larger than 1 MB, times the share of tails that contain
a high-bit byte. At hundreds of thousands of batches per day that is "a few times
a week", which is exactly what we saw. The driver's read path never validates
checksums at all (there is a `//TODO: validate checksum` in the code), so the
defect is visible only on INSERT.

Two tests pin it:

* `ClickHouseCityHashTest` compares the Java port with the C++ reference
  implementation compiled from the ClickHouse repository. For lengths 1..5000
  the outputs differ exactly at 1, 2, 3, 17, 18, 19 and never for ASCII-only
  input.
* `CompressedTailFrameITest` inserts one String row of every length in a window
  around 1 MB into a real ClickHouse (testcontainers, 21.1.9.41). With high-bit
  bytes at the end, exactly three lengths fail, with "Size of compressed block:
  17 / 18 / 19"; the same lengths with plain ASCII pass. One hundred percent
  reproducible.

## Why the old stack never saw it

This was the real puzzle. Same driver, same hash, deterministic code. We built
the same tests on Java 8 (1.8.0_452): bit-for-bit the same mismatches, the same
three failing lengths. The JVM was innocent. The old stack's error tracking was
alive and well, and it had never recorded this error. Both stacks inserted the
same columns in the same order, and the last column was a `DateTime`.

The answer was in the calendar.

A `DateTime` travels over the native protocol as a little-endian `UInt32` of
seconds. The tail frame consists of the last bytes of the last column, that is,
of the last `DateTime` in the batch, and the buggy branch sees:

| remainder of the packet | frame length | DateTime bytes in the hash | breaks when |
|---|---|---|---|
| 7 | 17 | byte 3 | never before 2026-11-20 (byte 3 is 0x6A) |
| 8 | 18 | bytes 2, 3 | byte 2 ≥ 0x80 |
| 9 | 19 | bytes 1, 2, 3 | byte 1 or byte 2 ≥ 0x80 |

Byte 1 changes every few minutes, so it is effectively random. Byte 2 changes
slowly, and it crossed 0x80 on **2026-08-15 05:58 UTC** (epoch `0x6A800000` =
1786773504). From that moment on, remainders 8 and 9 fail every time. Before it
(since 2026-05-10, `0x6A000000`), only remainder 9 could fail, and only when the
random byte 1 happened to be ≥ 0x80. Our new stack went live on September 1..3,
two weeks after the threshold. The coincidence with the Java 17 migration
created the illusion.

The second factor is batch size: a tail frame only exists when the `Data` packet
is larger than 1 MB. The old stack processed data more slowly, so its
time-triggered batches were mostly smaller than that; the new one is two to
three times faster and its batches are heavier.

`LiveDateTimeTailTest` pins the table above: a `(String, DateTime)` table, four
`DateTime` values with different high bits in bytes 1 and 2, three remainders.
All twelve predictions matched on the upstream driver; all twelve rows pass on
the fixed one. (The first version of the prediction scored 11 of 12: we had
forgotten that all three tail bytes enter the hash for remainder 9. The test
corrected the model.)

The bug has a calendar. On 2026-11-20 (`0x6B000000`) byte 2 wraps to 0x00 and
remainder 8 stops failing until 2027-02-25 (`0x6B800000`), after which the wave
returns. Anyone still on housepower 2.7.1 with a `DateTime` as the last inserted
column will see it come and go with a period of roughly half a year, looking each
time like random data corruption.

## The fix, and a workaround

This fork fixes the root cause: three `& 0xFF` masks in `hashLen0to16`, plus the
honest output bound in `CompressedBuffedWriter`, plus aircompressor 0.27
(CVE-2024-36114). Nothing else in the driver was touched. See
[SERPSTAT-FORK.md](../SERPSTAT-FORK.md) for the full list of changes and tests.

If you cannot change the driver, there is a workaround that follows directly
from the table above: **make the last column of your batched INSERT one whose
bytes are guaranteed below 0x80**, such as a small `UInt8` or `Enum8`. Then the
tail bytes never have the high bit and the buggy branch agrees with the server.
Be careful with the choice: `DateTime`, `Date` (its low byte is a day counter),
strings with non-ASCII characters, hashes and floats are all bad candidates, and
so is an HTTP status code stored as `UInt16` (200 is `C8 00`, 404 is `94 01`).
It is a patch over the symptom, though; the next person who appends a column
will step on the same mine.

## Takeaways

* Read the whole error message. "Size of compressed block: 18" was there from
  the first event.
* A theory that explains the symptom is not a theory that is true. Fuzz it.
* Signed `byte` is the most expensive keyword in Java. A port of a C hash
  function needs `& 0xFF` on every byte read, and a test against the reference
  implementation, not against itself.
* When a bug appears "right after the migration", check what else changed that
  week. Sometimes it is the date.
