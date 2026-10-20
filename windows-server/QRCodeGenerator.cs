using System;
using System.Collections;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using QRCoder;

namespace AirKeyboard
{
    /// <summary>
    /// An IPv4 address the app can reach this PC on, and the adapter it belongs to.
    /// </summary>
    public sealed record PairingAddress(IPAddress Address, string InterfaceName);

    /// <summary>
    /// Generates QR codes for pairing remote devices.
    /// The payload is the plain IPv4 address; the port is always 1978.
    /// The password is never part of it.
    /// </summary>
    public class QRCodeGenerator
    {
        private const int MaxPairingAddresses = 3;
        private const int QuietZoneModules = 4;

        /// <summary>
        /// Addresses worth showing, best first, at most 3: IPv4 on Up adapters (not loopback or tunnel),
        /// no 127/8 or 169.254/16. When any adapter has a default gateway, only those are kept
        /// (drops WSL/Hyper-V and gateway-less VPN adapters). Wi-Fi and Ethernet come first.
        /// </summary>
        public static IReadOnlyList<PairingAddress> GetPairingAddresses()
        {
            var candidates = new List<(PairingAddress Pairing, bool HasGateway, bool Preferred)>();
            foreach (NetworkInterface nic in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (nic.OperationalStatus != OperationalStatus.Up) continue;
                if (nic.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel) continue;

                IPInterfaceProperties props = nic.GetIPProperties();
                bool hasGateway = props.GatewayAddresses.Any(g =>
                    g.Address.AddressFamily == AddressFamily.InterNetwork && !g.Address.Equals(IPAddress.Any));
                bool preferred = nic.NetworkInterfaceType is NetworkInterfaceType.Wireless80211 or NetworkInterfaceType.Ethernet;

                foreach (UnicastIPAddressInformation unicast in props.UnicastAddresses)
                {
                    if (IsPairable(unicast.Address))
                    {
                        candidates.Add((new PairingAddress(unicast.Address, nic.Name), hasGateway, preferred));
                    }
                }
            }

            bool anyGateway = candidates.Any(c => c.HasGateway);
            return candidates
                .Where(c => c.HasGateway || !anyGateway)
                .OrderBy(c => c.Preferred ? 0 : 1) // stable: enumeration order is kept within each group
                .Select(c => c.Pairing)
                .DistinctBy(p => p.Address)
                .Take(MaxPairingAddresses)
                .ToList();
        }

        /// <summary>
        /// IPv4 unicast that the app's PairingCode parser accepts: first octet 1..223, not 127, not 169.254/16.
        /// </summary>
        private static bool IsPairable(IPAddress address)
        {
            if (address.AddressFamily != AddressFamily.InterNetwork) return false;
            byte[] b = address.GetAddressBytes();
            return b[0] >= 1 && b[0] <= 223 && b[0] != 127 && !(b[0] == 169 && b[1] == 254);
        }

        /// <summary>
        /// The QR payload for an address. Single source of truth for the format.
        /// </summary>
        public static string PayloadFor(IPAddress address) => address.ToString();

        /// <summary>
        /// Render a QR code as text, two module rows per line with half blocks.
        /// Light modules are ink, so print it light-on-dark (PrintPairing forces White on Black).
        /// Lines are joined with "\n"; every line starts and ends with the quiet zone.
        /// </summary>
        public static string RenderText(string payload)
        {
            using (QRCoder.QRCodeGenerator qrGenerator = new QRCoder.QRCodeGenerator())
            using (QRCodeData qrCodeData = qrGenerator.CreateQrCode(payload, QRCoder.QRCodeGenerator.ECCLevel.M))
            {
                List<BitArray> matrix = qrCodeData.ModuleMatrix;
                // QRCoder includes the 4-module quiet zone in ModuleMatrix; add one if it ever does not.
                int pad = HasQuietZone(matrix) ? 0 : QuietZoneModules;
                int size = matrix.Count + 2 * pad;

                bool IsLight(int row, int col)
                {
                    row -= pad;
                    col -= pad;
                    return row < 0 || col < 0 || row >= matrix.Count || col >= matrix.Count || !matrix[row][col];
                }

                var text = new StringBuilder();
                for (int row = 0; row < size; row += 2)
                {
                    if (row > 0) text.Append('\n');
                    for (int col = 0; col < size; col++)
                    {
                        // A row past the end (odd size) counts as light padding.
                        bool top = IsLight(row, col);
                        bool bottom = IsLight(row + 1, col);
                        text.Append(top ? (bottom ? '█' : '▀') : (bottom ? '▄' : ' '));
                    }
                }
                return text.ToString();
            }
        }

        private static bool HasQuietZone(List<BitArray> matrix)
        {
            int n = matrix.Count;
            if (n < 2 * QuietZoneModules) return false;
            for (int i = 0; i < n; i++)
            {
                for (int k = 0; k < QuietZoneModules; k++)
                {
                    if (matrix[k][i] || matrix[n - 1 - k][i] || matrix[i][k] || matrix[i][n - 1 - k]) return false;
                }
            }
            return true;
        }

        /// <summary>
        /// Print the pairing block to the console: instructions, then each address with its QR code.
        /// The optional heading is printed as the block's first line.
        /// </summary>
        public static void PrintPairing(string? heading = null)
        {
            IReadOnlyList<PairingAddress> addresses = GetPairingAddresses();

            // Console.Out is synchronized on itself, so holding it keeps log lines from
            // client threads out of the block while the colors are switched per line.
            lock (Console.Out)
            {
                Console.WriteLine();
                if (!string.IsNullOrEmpty(heading)) Console.WriteLine(heading);

                if (addresses.Count == 0)
                {
                    Console.WriteLine("No network connection found. Connect this PC to Wi-Fi or Ethernet, then restart the server to show a QR code.");
                    Console.WriteLine();
                    return;
                }

                Console.WriteLine("Scan a QR code with the app (Scan QR code), or type the address:");
                foreach (PairingAddress pairing in addresses)
                {
                    string payload = PayloadFor(pairing.Address);
                    Console.WriteLine($"{pairing.InterfaceName}: {payload}");
                    foreach (string line in RenderText(payload).Split('\n'))
                    {
                        Console.ForegroundColor = ConsoleColor.White;
                        Console.BackgroundColor = ConsoleColor.Black;
                        Console.Write(line);
                        Console.ResetColor(); // before the newline, so a scrolled-in line is not painted black
                        Console.WriteLine();
                    }
                    Console.WriteLine();
                }
            }
        }

        /// <summary>
        /// Render a QR code as a bitmap, dark on white with the quiet zone (the tray's pairing window).
        /// </summary>
        public static Bitmap GenerateBitmap(string payload, int pixelsPerModule = 10)
        {
            using (QRCoder.QRCodeGenerator qrGenerator = new QRCoder.QRCodeGenerator())
            using (QRCodeData qrCodeData = qrGenerator.CreateQrCode(payload, QRCoder.QRCodeGenerator.ECCLevel.M))
            using (QRCode qrCode = new QRCode(qrCodeData))
            {
                return qrCode.GetGraphic(pixelsPerModule);
            }
        }

        /// <summary>
        /// Save QR code to file
        /// </summary>
        public static void SaveQRCodeToFile(Bitmap qrCode, string filePath)
        {
            try
            {
                qrCode.Save(filePath, ImageFormat.Png);
            }
            catch (Exception ex)
            {
                throw new Exception($"Failed to save QR code: {ex.Message}", ex);
            }
        }
    }
}
