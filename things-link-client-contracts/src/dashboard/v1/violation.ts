import type { DashboardRejectionReason } from "./generated.js";

/** Dashboard合同拒绝结果，稳定携带原因和JSON路径。 */
export class DashboardContractViolation extends Error {
  /** 稳定拒绝原因。 */
  public readonly reason: DashboardRejectionReason;
  /** 拒绝位置的稳定JSON路径。 */
  public readonly path: string;

  /**
   * 创建合同拒绝结果。
   *
   * @param reason 稳定拒绝原因。
   * @param path 稳定JSON路径。
   * @param detail 面向开发者的说明。
   */
  public constructor(reason: DashboardRejectionReason, path: string, detail: string) {
    super(`${path} ${detail}`);
    this.name = "DashboardContractViolation";
    this.reason = reason;
    this.path = path;
  }
}
