export const title = "Activity / CouponTemplate Publish and Activation Job Lifecycle (Fixed)";

export const state = (v) =>
  `t=${v.now} | DB=${v.dbStatus} | Redis=${v.redisStatus}${v.jobTickedAfter ? " (job ran)" : ""}`;

export const step = (action) => {
  switch (action) {
    case "AdvanceToDuring": return "startTime reached";
    case "AdvanceToAfter": return "endTime reached";
    case "Publish": return "admin calls publish()";
    case "JobActivatePreheated": return "job activates preheated (0->1)";
    case "JobEndExpired": return "job ends active or preheated (1/0->2)";
    case "JobTickIdleAfter": return "job runs after endTime (draft/ended idle)";
    case "Done": return null;
    default: return action;
  }
};
