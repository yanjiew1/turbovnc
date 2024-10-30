/* Copyright (C) 2012, 2018 D. R. Commander.  All Rights Reserved.
 * Copyright (C) 2011 Brian P. Hinz
 * Copyright (C) 2002-2005 RealVNC Ltd.  All Rights Reserved.
 *
 * This is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301,
 * USA.
 */

//
// A ZlibInStream reads from a zlib.io.InputStream
//

package com.turbovnc.rdr;
import java.util.zip.Inflater;
import java.util.zip.DataFormatException;

public class ZlibInStream extends InStream {

  static final int DEFAULT_BUF_SIZE = 16384;

  public ZlibInStream(int bufSize_) {
    bufSize = bufSize_;
    b = new byte[bufSize];
    bytesIn = offset = 0;
    inflater = new Inflater();
    needsInput = true;
    ptr = end = start = 0;
  }

  public ZlibInStream() { this(DEFAULT_BUF_SIZE); }

  public void close() {
    b = null;
    inflater.end();
  }

  public void setUnderlying(InStream is, int bytesIn_) {
    underlying = is;
    bytesIn = bytesIn_;
    ptr = end = start;
  }

  public int pos() {
    return offset + ptr - start;
  }

  public void reset() {
    ptr = end = start;

    while (bytesIn > 0) {
      decompress(true);
      ptr = end = start;
    }

    try {
      int buflen = bufSize - start;
      while (inflater.inflate(b, start, buflen) == buflen);
    } catch (DataFormatException e) {
      throw new ErrorException("ZlibInStream: inflate failed");
    }

    needsInput = true;
    setUnderlying(null, 0);
  }

  protected int overrun(int itemSize, int nItems, boolean wait) {
    if (itemSize > bufSize)
      throw new ErrorException("ZlibInStream overrun: max itemSize exceeded");

    if (end - ptr != 0)
      System.arraycopy(b, ptr, b, start, end - ptr);

    offset += ptr - start;
    end -= ptr - start;
    ptr = start;

    while (end - ptr < itemSize) {
      if (!decompress(wait))
        return 0;
    }

    if (itemSize * nItems > end - ptr)
      nItems = (end - ptr) / itemSize;

    return nItems;
  }

  // decompress() calls the decompressor once. 
  //
  // It is possible that the decompressor generate some output data without
  // consuming any input data, or generate no output data while consuming input
  // data. Returns false if wait is false and we would block on the underlying
  // stream.
  private boolean decompress(boolean wait) {
    if (underlying == null)
      throw new ErrorException("ZlibInStream overrun: no underlying stream");
    if (inflater.finished())
      throw new ErrorException("ZlibInStream decompress: end of zlib stream");

    if (needsInput) {
      int n = underlying.check(1, 1, wait);
      if (n == 0) return false;
      needsInput = false;
    }

    try {
      int underlyingPtr = underlying.getptr();
      int inputLen = underlying.getend() - underlyingPtr;
      int outputLen = bufSize - end;
      if (inputLen > bytesIn)
        inputLen = bytesIn;

      inflater.setInput(underlying.getbuf(), underlyingPtr, inputLen);
      int bytesOut = inflater.inflate(b, end, outputLen);
      int bytesConsumed = inputLen - inflater.getRemaining();

      // If output buffer is not full, it is garanteed that there is no
      // pending output data in the inflater itself.
      //
      // The Inflater.needsInput() only checks if the input buffer is empty,
      // which is not sufficient because the inflater itself may have some
      // pending output data.
      if (bytesOut < outputLen)
        needsInput = true;

      end += bytesOut;
      bytesIn -= bytesConsumed;
      underlying.setptr(underlyingPtr + bytesConsumed);
    } catch (DataFormatException e) {
      throw new ErrorException("ZlibInStream: inflate failed");
    }

    return true;
  }

  private InStream underlying;
  private int bufSize;
  private int offset;
  private Inflater inflater;
  private boolean needsInput;
  private int bytesIn;
  private int start;
}
