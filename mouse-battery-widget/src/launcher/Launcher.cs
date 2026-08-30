// MouseBatteryWidget の起動用スタブ (約 5KB)。
//
// なぜ必要か:
//   Windows 11 のタスクマネージャー「スタートアップ アプリ」は、登録名ではなく
//   起動する実行ファイルの FileDescription を表示する。javaw.exe を直接登録すると
//   「javaw」と出てしまうため、"MouseBatteryWidget" という説明を持つこの薄い
//   ランチャを噛ませて、その名前で表示させる。
//
// 動作: 自分と同じフォルダの MouseBatteryWidget.jar を javaw で起動して即終了する。
//
// ビルド (build.ps1 が自動でやる):
//   csc /target:winexe /out:MouseBatteryWidget.exe Launcher.cs

using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;

[assembly: AssemblyTitle("MouseBatteryWidget")]
[assembly: AssemblyProduct("MouseBatteryWidget")]
[assembly: AssemblyCompany("MouseBatteryWidget")]
[assembly: AssemblyFileVersion("1.0.0.0")]
[assembly: AssemblyVersion("1.0.0.0")]

internal static class Launcher
{
    private static void Main()
    {
        string dir = AppDomain.CurrentDomain.BaseDirectory;
        string jar = Path.Combine(dir, "MouseBatteryWidget.jar");

        string javaw = @"C:\Program Files\jdk-26.0.1\bin\javaw.exe";
        if (!File.Exists(javaw))
        {
            javaw = "javaw.exe"; // fall back to PATH
        }

        var psi = new ProcessStartInfo(javaw, "-jar \"" + jar + "\"")
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            WorkingDirectory = dir,
        };

        try
        {
            Process.Start(psi);
        }
        catch
        {
            // JDK が見つからない等。ここで失敗しても常駐アプリ側の責務ではない。
        }
    }
}
