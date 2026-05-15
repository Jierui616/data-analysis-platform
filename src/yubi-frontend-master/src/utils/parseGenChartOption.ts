/**
 * 将后端/模型返回的 genChart 解析为可交给 ECharts 的 option 对象。
 * 与「我的图表」回看共用，避免智能分析页与列表页解析策略不一致。
 */

function stripBom(s: string): string {
  if (s.length > 0 && s.charCodeAt(0) === 0xfeff) {
    return s.slice(1);
  }
  return s;
}

/** 与后端 BiAiResultParser 一致：中文弯引号会破坏 JSON.parse */
function normalizeJsonishText(s: string): string {
  return s.replace(/\u201c/g, '"').replace(/\u201d/g, '"');
}

function unwrapMarkdownFence(s: string): string {
  const t = s.trim();
  const wrapped = t.match(/```(?:json)?\s*([\s\S]*?)```/i);
  if (wrapped) {
    return wrapped[1].trim();
  }
  return t;
}

function tryParseJson(s: string): unknown {
  try {
    return JSON.parse(s);
  } catch {
    return undefined;
  }
}

/** 若整段是 JSON 字符串（外层带引号），解一层得到内层 JSON 文本 */
function unwrapDoubleEncodedJsonString(s: string): string {
  const t = s.trim();
  if (t.length < 2 || t[0] !== '"' || t[t.length - 1] !== '"') {
    return t;
  }
  const inner = tryParseJson(t);
  return typeof inner === 'string' ? inner : t;
}

/**
 * 从首个 `{` 起抽取与之平衡的最外层对象子串（双引号字符串内的括号不计入深度）。
 */
function extractBalancedJsonObject(s: string): string | undefined {
  const start = s.indexOf('{');
  if (start < 0) {
    return undefined;
  }
  let depth = 0;
  let inStr = false;
  let esc = false;
  for (let i = start; i < s.length; i++) {
    const c = s[i];
    if (inStr) {
      if (esc) {
        esc = false;
        continue;
      }
      if (c === '\\') {
        esc = true;
        continue;
      }
      if (c === '"') {
        inStr = false;
      }
      continue;
    }
    if (c === '"') {
      inStr = true;
      continue;
    }
    if (c === '{') {
      depth++;
    } else if (c === '}') {
      depth--;
      if (depth === 0) {
        return s.slice(start, i + 1);
      }
    }
  }
  return undefined;
}

function hasValidSeries(option: unknown): boolean {
  if (!option || typeof option !== 'object' || Array.isArray(option)) {
    return false;
  }
  const series = (option as { series?: unknown }).series;
  return (
    (Array.isArray(series) && series.some((item) => item && typeof item === 'object')) ||
    (!!series && !Array.isArray(series) && typeof series === 'object')
  );
}

export type GenChartRaw = string | Record<string, unknown> | undefined | null;

/**
 * @returns 可渲染的 option；无法解析或缺少 series 时返回 undefined
 */
export function parseGenChartOption(raw: GenChartRaw): Record<string, unknown> | undefined {
  if (raw == null) {
    return undefined;
  }
  if (typeof raw === 'object' && !Array.isArray(raw)) {
    return hasValidSeries(raw) ? raw : undefined;
  }
  if (typeof raw !== 'string') {
    return undefined;
  }

  let s = stripBom(raw.trim());
  if (!s) {
    return undefined;
  }
  s = unwrapDoubleEncodedJsonString(s);
  s = normalizeJsonishText(stripBom(unwrapMarkdownFence(s)));

  let parsed: unknown = tryParseJson(s);
  if (typeof parsed === 'object' && parsed && hasValidSeries(parsed)) {
    return parsed as Record<string, unknown>;
  }

  const extracted = extractBalancedJsonObject(s);
  if (extracted) {
    const inner = normalizeJsonishText(extracted);
    parsed = tryParseJson(inner);
    if (typeof parsed === 'object' && parsed && hasValidSeries(parsed)) {
      return parsed as Record<string, unknown>;
    }
  }

  return undefined;
}
