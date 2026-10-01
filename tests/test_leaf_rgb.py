"""Regression for the production MipGen base-level upload/dilation logic.

Run with Java 21+ on PATH:
    python3 -m unittest discover -s tests -v
Optional VOXY_LEAF_PACK points to ModernBeta.zip (requires Pillow).
Compiles the actual MipGen source with small in-memory dependency doubles.
Mip averaging is a double: this checks base texels, not mip colour arithmetic.
"""
from pathlib import Path
import io
import os
import struct
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MODEL = ROOT / 'src/main/java/me/cortex/voxy/client/core/model'


class LeafRgbTest(unittest.TestCase):
    def test_low_alpha_rgb_survives_upload(self):
        fixtures = {'synthetic': [0x01363636 if i % 3 == 0 else 0xFF969696 for i in range(256)]}
        if pack := os.environ.get('VOXY_LEAF_PACK'):
            from PIL import Image
            with zipfile.ZipFile(pack) as z:
                for leaf in ('oak', 'spruce'):
                    image = Image.open(io.BytesIO(z.read(f'assets/minecraft/textures/block/{leaf}_leaves.png'))).convert('RGBA')
                    self.assertEqual(image.size, (16, 16))
                    fixtures[leaf] = [r | g << 8 | b << 16 | a << 24 for r, g, b, a in image.getdata()]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def put(name, text):
                p = root / name
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text(text)
            for name in ('MipGen', 'ColourDepthTextureData'):
                put(f'me/cortex/voxy/client/core/model/{name}.java', (MODEL / f'{name}.java').read_text())
            put('me/cortex/voxy/client/core/model/ModelFactory.java', '''package me.cortex.voxy.client.core.model;
public class ModelFactory { public static final int MODEL_TEXTURE_SIZE=16, LAYERS=4; }''')
            put('me/cortex/voxy/client/core/model/TextureUtils.java', '''package me.cortex.voxy.client.core.model;
public class TextureUtils {
 public static int mipColours(boolean darkened,int a,int b,int c,int d) { return a; }
}''')
            put('me/cortex/voxy/common/util/MemoryBuffer.java', '''package me.cortex.voxy.common.util;
public class MemoryBuffer { public final long address=0; }''')
            put('net/caffeinemc/mods/sodium/client/util/color/ColorSRGB.java', '''package net.caffeinemc.mods.sodium.client.util.color;
public class ColorSRGB {}''')
            put('org/lwjgl/system/MemoryUtil.java', '''package org.lwjgl.system;
public class MemoryUtil {
 private static final int[] DATA=new int[4096];
 public static int memGetInt(long p) { return DATA[Math.toIntExact(p/4)]; }
 public static void memPutInt(long p,int v) { DATA[Math.toIntExact(p/4)]=v; }
}''')
            put('it/unimi/dsi/fastutil/bytes/ByteArrayFIFOQueue.java', '''package it.unimi.dsi.fastutil.bytes;
public class ByteArrayFIFOQueue {
 private final java.util.ArrayDeque<Byte> q=new java.util.ArrayDeque<>();
 public ByteArrayFIFOQueue(int size) {}
 public void enqueue(byte b) { q.add(b); }
 public byte dequeueByte() { return q.remove(); }
 public boolean isEmpty() { return q.isEmpty(); }
}''')
            put('LeafRgbCheck.java', '''import java.nio.*;
import java.nio.file.*;
import me.cortex.voxy.client.core.model.*;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.system.MemoryUtil;
public class LeafRgbCheck {
 public static void main(String[] args) throws Exception {
  var input=ByteBuffer.wrap(Files.readAllBytes(Path.of(args[0]))).order(ByteOrder.LITTLE_ENDIAN);
  int[] pixels=new int[256]; for(int i=0;i<256;i++) pixels[i]=input.getInt();
  var faces=new ColourDepthTextureData[6];
  for(int i=0;i<6;i++) faces[i]=new ColourDepthTextureData(pixels,new int[256],16,16);
  for(boolean darkened:new boolean[]{false,true}) {
   MipGen.putTextures(darkened,faces,new MemoryBuffer());
   for(int f=0;f<6;f++) for(int i=0;i<256;i++) {
    int x=(f>>1)*16+i%16, y=(f&1)*16+i/16;
    if(MemoryUtil.memGetInt((x+y*48)*4L)!=pixels[i])
     throw new AssertionError("Base RGB/alpha changed: face="+f+" pixel="+i+" darkened="+darkened);
   }
  }
 }
}''')
            subprocess.run(['javac', '-d', str(root), *map(str, root.rglob('*.java'))], check=True, capture_output=True)
            for name, pixels in fixtures.items():
                with self.subTest(texture=name):
                    data = root / f'{name}.rgba'
                    data.write_bytes(struct.pack('<256I', *pixels))
                    subprocess.run(['java', '-cp', str(root), 'LeafRgbCheck', str(data)], check=True, capture_output=True)


if __name__ == '__main__':
    unittest.main()
