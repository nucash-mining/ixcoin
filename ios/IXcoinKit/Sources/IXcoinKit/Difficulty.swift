import Foundation

/// iXcoin's retarget rules, which differ from Bitcoin's in three ways.
public enum Difficulty {
    /// iXcoin moved to a one-day retarget window after height 20055.
    public static func isRevised(height: Int) -> Bool { height > ChainParams.revisedHeight }

    public static func targetTimespan(height: Int) -> Int {
        isRevised(height: height) ? ChainParams.targetTimespanRevised
                                  : ChainParams.targetTimespanOriginal
    }

    public static func interval(height: Int) -> Int {
        targetTimespan(height: height) / ChainParams.targetSpacing
    }

    /// How far back the window starts.
    ///
    /// Bitcoin walks back `interval - 1` blocks, an off-by-one it never fixed.
    /// iXcoin kept that until height 43000 and uses the full interval from
    /// there — except exactly at `height == interval`, which stays on the old
    /// path. Getting this wrong only diverges at a retarget, so it can look
    /// correct for hundreds of blocks before rejecting one.
    public static func blocksToGoBack(height: Int) -> Int {
        let n = interval(height: height)
        if height >= ChainParams.fullWindowHeight && height != n { return n }
        return n - 1
    }

    public static func isRetargetHeight(_ height: Int) -> Bool {
        height % interval(height: height) == 0
    }

    /// Clamp the observed timespan the way the consensus code does.
    public static func clampTimespan(_ actual: Int, height: Int) -> Int {
        let target = targetTimespan(height: height)
        return min(max(actual, target / 4), target * 4)
    }
}
