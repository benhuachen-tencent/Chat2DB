/**
 * 自动选表工具：根据用户输入的自然语言，从候选表中挑选最相关的表
 *
 * 关键设计：
 *   用户输入常是中文（如"测算单汇总物化表"），而表名通常是英文（如 mt_estimation_order_summary）。
 *   最可靠的中英桥梁其实是 **表的 comment 注释**（DBA 写表时填的中文描述）。
 *   所以本实现优先用 comment 做中文匹配，再辅以中英词典翻译，最后才是英文字符级匹配。
 *
 * 打分策略（分数越高越相关）：
 *  1. 完整表名在输入中出现 => +100
 *  2. 表 comment 的字符 n-gram（2/3/4 字连续中文串）出现在输入中 => 每命中一个 n-gram 加分
 *       - 命中的中文串越长越精准；同时与 comment 本身长度成比例（覆盖度）
 *  3. 表名英文子词被输入直接包含 => 每词 +30
 *  4. 用户中文输入 → 词典翻译出的英文词袋 → 与表名子词匹配 => 每词 +25
 *  5. 表名 4+ 字符连续子串出现在输入中（弱兜底） => +10
 */

export interface ITableMeta {
  name: string;
  comment?: string;
}

export interface IScoredTable {
  name: string;
  score: number;
  detail: string;
}

/**
 * 常见业务词中英词典（作为 comment 匹配之外的补充）。
 */
const CN_EN_DICT: Record<string, string[]> = {
  合同: ['contract'],
  协议: ['agreement', 'contract'],
  客户: ['customer', 'client'],
  用户: ['user', 'account', 'member'],
  账号: ['account', 'user'],
  成员: ['member', 'user'],
  员工: ['employee', 'staff', 'user'],
  项目: ['project'],
  订单: ['order'],
  测算: ['estimation', 'estimate', 'calc', 'calculation'],
  测算单: ['estimation', 'estimate'],
  预估: ['estimation', 'estimate'],
  核算: ['calculation', 'calc', 'accounting'],
  商品: ['product', 'item', 'goods'],
  产品: ['product'],
  公司: ['company', 'org', 'organization'],
  组织: ['org', 'organization'],
  部门: ['department', 'dept'],
  供应商: ['supplier', 'vendor'],
  站点: ['site'],
  局点: ['site'],
  设备: ['device', 'equipment'],
  资产: ['asset'],
  角色: ['role'],
  权限: ['permission', 'privilege', 'auth'],
  菜单: ['menu'],
  字典: ['dict', 'dictionary'],
  配置: ['config', 'configuration', 'setting'],
  参数: ['param', 'parameter', 'config'],
  日志: ['log'],
  历史: ['history', 'record', 'log'],
  记录: ['record', 'log'],
  审计: ['audit'],
  事件: ['event'],
  告警: ['alert', 'alarm', 'warning'],
  通知: ['notification', 'notify', 'message'],
  消息: ['message', 'msg', 'notification'],
  附件: ['attachment', 'file'],
  文件: ['file', 'attachment'],
  图片: ['image', 'picture', 'img'],
  标签: ['tag', 'label'],
  分类: ['category', 'classification', 'class'],
  类型: ['type'],
  状态: ['status', 'state'],
  地址: ['address', 'addr', 'location'],
  位置: ['location', 'position', 'site'],
  结果: ['result'],
  明细: ['detail', 'item'],
  详情: ['detail', 'info'],
  任务: ['task', 'job'],
  作业: ['job', 'task'],
  计划: ['plan', 'schedule'],
  时间表: ['schedule'],
  支付: ['pay', 'payment'],
  付款: ['payment', 'pay'],
  收款: ['receipt', 'payment'],
  发票: ['invoice'],
  财务: ['finance', 'financial'],
  收入: ['income', 'revenue'],
  支出: ['expense', 'cost'],
  成本: ['cost', 'expense'],
  价格: ['price'],
  评论: ['comment', 'review'],
  评价: ['review', 'rating', 'comment'],
  评分: ['rating', 'score'],
  投诉: ['complaint'],
  反馈: ['feedback'],
  问题: ['issue', 'question', 'problem'],
  故障: ['fault', 'failure', 'issue'],
  工单: ['ticket', 'order', 'workorder'],
  聊天: ['chat', 'conversation'],
  会话: ['session', 'conversation'],
  基础: ['basic', 'base'],
  基本: ['basic', 'base'],
  信息: ['info', 'information'],
  数据: ['data'],
  结构: ['structure', 'struct', 'schema'],
  结构化: ['structured', 'structure'],
  分析: ['analysis', 'analyze', 'analytics'],
  统计: ['stat', 'statistics', 'stats'],
  汇总: ['summary', 'sum', 'aggregation', 'agg'],
  物化: ['materialized', 'summary'],
  物化表: ['summary', 'materialized'],
  报表: ['report'],
  报告: ['report'],
  请求: ['request', 'req'],
  响应: ['response', 'resp'],
  操作: ['operation', 'action', 'op'],
  行为: ['action', 'behavior', 'event'],
  维护: ['maintenance', 'maintain'],
  维保: ['maintenance', 'maintain'],
  保修: ['maintenance', 'warranty'],
  服务: ['service'],
  授权: ['authorization', 'auth', 'license'],
  许可: ['license', 'permit'],
  结论: ['conclusion'],
  采购: ['purchase'],
  销售: ['sale', 'sales'],
  售卖: ['sale', 'sales', 'sell'],
  库存: ['stock', 'inventory'],
  仓库: ['warehouse', 'repo'],
  临时: ['tmp', 'temp', 'temporary'],
  暂存: ['tmp', 'temp'],
  备份: ['backup', 'bak'],
  草稿: ['draft'],
  模板: ['template', 'tpl'],
  版本: ['version', 'ver'],
  证书: ['certificate', 'cert'],
  密钥: ['key', 'secret'],
  令牌: ['token'],
  登录: ['login', 'signin'],
  注册: ['register', 'signup'],
  密码: ['password', 'pwd', 'pass'],
  // 数量/时间
  数量: ['count', 'num', 'number', 'qty', 'quantity'],
  表: [],
  下: [],
  有: [],
  中: [],
  从: [],
  多少: [],
};

/** 将英文表名拆为子词 */
function splitEnglishTokens(name: string): string[] {
  if (!name) return [];
  const parts = name.split(/[_\-\s]+/);
  const tokens: string[] = [];
  parts.forEach((p) => {
    const camelSplit = p.replace(/([a-z])([A-Z])/g, '$1 $2').split(/\s+/);
    camelSplit.forEach((t) => {
      const low = t.toLowerCase();
      if (low.length >= 2) tokens.push(low);
    });
  });
  return tokens;
}

/** 从用户中文输入里提取所有命中词典的中文关键词，并翻译成英文候选词袋 */
function translateCnToEnTokens(input: string): { cnHits: string[]; enTokens: Set<string> } {
  const enTokens = new Set<string>();
  const cnHits: string[] = [];
  const keys = Object.keys(CN_EN_DICT).sort((a, b) => b.length - a.length);
  const cnOnly = input.replace(/[a-zA-Z0-9_\s\-"'.,?!，。？！、；：""'']+/g, '');
  const used = new Array(cnOnly.length).fill(false);
  for (const key of keys) {
    if (!key) continue;
    let from = 0;
    while (from <= cnOnly.length - key.length) {
      const idx = cnOnly.indexOf(key, from);
      if (idx < 0) break;
      let overlap = false;
      for (let i = idx; i < idx + key.length; i++) {
        if (used[i]) {
          overlap = true;
          break;
        }
      }
      if (!overlap) {
        for (let i = idx; i < idx + key.length; i++) used[i] = true;
        cnHits.push(key);
        (CN_EN_DICT[key] || []).forEach((en) => enTokens.add(en.toLowerCase()));
      }
      from = idx + 1;
    }
  }
  return { cnHits, enTokens };
}

/** 只保留中文字符 */
function chineseOnly(s: string): string {
  return (s || '').replace(/[^\u4e00-\u9fa5]/g, '');
}

/**
 * 计算 comment 与用户输入的中文 n-gram 重合度。
 *  - 生成 comment 的 2/3/4-gram 中文子串
 *  - 每个在用户输入中出现的子串 ⇒ +(len * 权重系数)
 *  - 同时去重（命中短串被更长串包含时不重复计分）
 *
 * 额外：命中的中文总字符数 / comment 中文总长度 = 覆盖率，给予系数奖励。
 */
function scoreCommentByNgramOverlap(
  comment: string,
  userInputCn: string,
): { score: number; hitNgrams: string[] } {
  const commentCn = chineseOnly(comment);
  if (!commentCn || !userInputCn) return { score: 0, hitNgrams: [] };

  const hitSet = new Set<string>();
  // 生成所有 2-4 字中文 n-gram
  for (const n of [4, 3, 2]) {
    for (let i = 0; i + n <= commentCn.length; i++) {
      const gram = commentCn.slice(i, i + n);
      if (userInputCn.includes(gram)) {
        // 如果已有更长的子串包含它，则跳过
        let containedInLonger = false;
        for (const existing of hitSet) {
          if (existing.length > gram.length && existing.includes(gram)) {
            containedInLonger = true;
            break;
          }
        }
        if (!containedInLonger) hitSet.add(gram);
      }
    }
  }
  if (hitSet.size === 0) return { score: 0, hitNgrams: [] };

  // 总命中字符数（去重后；每个 gram 按长度计）
  const hitChars = Array.from(hitSet).reduce((acc, g) => acc + g.length, 0);
  const coverage = Math.min(1, hitChars / commentCn.length);

  // 打分：每命中一个 n-gram，基础分 = len * 15（更长越稀有越重要），再按覆盖率放大（1.0~1.5）
  const base = Array.from(hitSet).reduce((acc, g) => acc + g.length * 15, 0);
  const score = Math.round(base * (1 + coverage * 0.5));
  return { score, hitNgrams: Array.from(hitSet) };
}

/** 生成字符串的连续子串（英文兜底用） */
function genSubstrings(s: string, minLen: number): string[] {
  const out: string[] = [];
  const low = s.toLowerCase();
  for (let i = 0; i <= low.length - minLen; i++) {
    for (let j = i + minLen; j <= low.length; j++) {
      out.push(low.slice(i, j));
    }
  }
  return out;
}

/**
 * 从候选表中挑选与用户输入最相关的 topK 张表
 *
 * @param userInput 用户的自然语言输入（中/英/混合）
 * @param tables 当前库里的表元信息列表（name + 可选 comment）
 * @param topK 最多返回几张（默认 3）
 */
export function autoSelectTables(userInput: string, tables: ITableMeta[], topK = 3): string[] {
  if (!userInput || !tables?.length) return [];
  const inputLow = userInput.toLowerCase();
  const userInputCn = chineseOnly(userInput);

  const { cnHits, enTokens: translatedEn } = translateCnToEnTokens(userInput);
  (userInput.match(/[a-zA-Z][a-zA-Z0-9_]{1,}/g) || []).forEach((w) => translatedEn.add(w.toLowerCase()));

  // eslint-disable-next-line no-console
  console.log(
    '[autoSelectTables] input:',
    userInput,
    '| cnHits:',
    cnHits,
    '| enTokens:',
    Array.from(translatedEn),
    '| userInputCn:',
    userInputCn,
  );

  const scored: IScoredTable[] = tables.map((t) => {
    let score = 0;
    const detail: string[] = [];
    const name = t.name || '';
    const comment = t.comment || '';
    const nameLow = name.toLowerCase();
    const nameTokens = splitEnglishTokens(name);

    // 1. 完整表名出现在输入中
    if (nameLow && inputLow.includes(nameLow)) {
      score += 100;
      detail.push('fullName+100');
    }

    // 2. comment 的中文 n-gram 与输入的重合度（最强跨语言信号）
    if (comment && userInputCn) {
      const { score: cScore, hitNgrams } = scoreCommentByNgramOverlap(comment, userInputCn);
      if (cScore > 0) {
        score += cScore;
        detail.push(`comment[${comment}]~[${hitNgrams.join('|')}]+${cScore}`);
      }
    }

    // 3. 表名英文子词直接命中输入
    nameTokens.forEach((tok) => {
      if (tok.length >= 3 && inputLow.includes(tok)) {
        score += 30;
        detail.push(`en:${tok}+30`);
      }
    });

    // 4. 中文→英文翻译后的词袋匹配表名子词
    nameTokens.forEach((tok) => {
      if (tok.length < 2) return;
      for (const en of translatedEn) {
        if (!en || en.length < 2) continue;
        if (tok === en || tok.startsWith(en) || en.startsWith(tok)) {
          score += 25;
          detail.push(`cn→en:${en}~${tok}+25`);
          break;
        }
      }
    });

    // 5. 英文子串弱兜底
    if (score === 0 && nameLow.length >= 4) {
      const subs = genSubstrings(nameLow, 4);
      for (const s of subs) {
        if (inputLow.includes(s)) {
          score += 10;
          detail.push(`sub:${s}+10`);
          break;
        }
      }
    }

    return { name, score, detail: detail.join(',') };
  });

  const hits = scored.filter((s) => s.score > 0).sort((a, b) => b.score - a.score);

  // eslint-disable-next-line no-console
  console.log(
    '[autoSelectTables] scored TOP 10:',
    hits.slice(0, 10).map((h) => `${h.name}=${h.score}(${h.detail})`),
  );

  return hits.slice(0, topK).map((s) => s.name);
}
