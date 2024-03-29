﻿using System.Text;
using Array = System.Array;

namespace TvRename.Subtitles;

public static class OpenSubtitlesHasher
{
    public static string ComputeMovieHash(string filename)
    {
        byte[] result;
        using (Stream input = File.OpenRead(filename))
        {
            result = ComputeMovieHash(input);
        }

        return ToHexadecimal(result);
    }

    private static byte[] ComputeMovieHash(Stream input)
    {
        long lhash, streamsize;
        streamsize = input.Length;
        lhash = streamsize;

        long i = 0;
        var buffer = new byte[sizeof(long)];
        while (i < 65536 / sizeof(long) && input.Read(buffer, 0, sizeof(long)) > 0)
        {
            i++;
            lhash += BitConverter.ToInt64(buffer, 0);
        }

        input.Position = Math.Max(0, streamsize - 65536);
        i = 0;
        while (i < 65536 / sizeof(long) && input.Read(buffer, 0, sizeof(long)) > 0)
        {
            i++;
            lhash += BitConverter.ToInt64(buffer, 0);
        }

        input.Close();
        byte[] result = BitConverter.GetBytes(lhash);
        Array.Reverse(result);
        return result;
    }

    private static string ToHexadecimal(byte[] bytes)
    {
        var hexBuilder = new StringBuilder();
        for (var i = 0; i < bytes.Length; i++)
        {
            hexBuilder.Append(bytes[i].ToString("x2"));
        }

        return hexBuilder.ToString();
    }
}
