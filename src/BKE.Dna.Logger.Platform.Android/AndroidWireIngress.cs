using System.Text;
using BKE.Dna.Logger.Core.Protocol;

namespace BKE.Dna.Logger.Platform.Android;

internal sealed class AndroidWireIngress
{
    public AndroidWireReceipt ValidateNativeMessage(string json)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(json);
        var utf8 = Encoding.UTF8.GetBytes(json);
        var type = DnaWireProtocol.ReadAndValidateType(utf8);
        return new AndroidWireReceipt(type, utf8.Length);
    }
}

internal sealed record AndroidWireReceipt(string Type, int Utf8ByteLength);
