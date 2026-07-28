# Analysis Depth Choice Alias Design

## Goal

Recognize `完整的投资分析` as the full-analysis answer to the existing analysis-depth clarification.

## Change

Add `完整的投资分析` to `AnalysisDepthFollowUpResolver.FULL_CHOICES`.

No normalization, matching, routing, persistence, or prompt behavior will change. No test will be added, per the requested scope.

## Expected Behavior

When the preceding assistant message is the existing analysis-depth clarification and the user replies `完整的投资分析`, the resolver selects `Choice.FULL`, restores the preceding financial query, and routes the combined request as `STOCK_ANALYSIS`.

All other inputs retain their current behavior.
