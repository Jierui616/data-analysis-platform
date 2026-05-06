import {
  createChatSessionUsingPOST,
  deepChatUsingPOST,
  listChatSessionMessagesUsingGET,
  listChatSessionsUsingGET,
} from '@/services/yubi/biCenterController';
import { useModel, useNavigate } from '@umijs/max';
import {
  Badge,
  Button,
  Card,
  Col,
  Divider,
  Drawer,
  Empty,
  Grid,
  Input,
  List,
  message,
  Row,
  Select,
  Space,
  Tag,
  Typography,
} from 'antd';
import React, { useEffect, useMemo, useState } from 'react';
import './index.less';

type ChatRole = 'user' | 'assistant';
type BizRole = 'manager' | 'operator' | 'investor';
type ScreenViewKey = 'hq' | 'store' | 'region';

type ActionItem = {
  priority?: string;
  title?: string;
  owner?: string;
  expectedBenefit?: string;
  eta?: string;
};

type ChatMessage = {
  role: ChatRole;
  content: string;
  ts: number;
  actionItems?: ActionItem[];
  thinking?: boolean;
};

type SessionMeta = {
  sessionId: string;
  title: string;
  updatedAt: number;
};

type PromptGroup = {
  key: string;
  title: string;
  tag: string;
  prompts: string[];
};

const LOCAL_SESSIONS_KEY = 'deep_chat_sessions_v1';
const LOCAL_MESSAGES_KEY = 'deep_chat_messages_v1';
const LOCAL_ACTION_DRAFTS_KEY = 'deep_chat_action_drafts_v1';
const BI_OP_VIEW_STORAGE_KEY = 'bi_operation_screen_view';

const quickPromptGroups: PromptGroup[] = [
  {
    key: 'root-cause',
    title: '异常归因',
    tag: '高频',
    prompts: ['今天门店销售下滑最可能的原因是什么？请按优先级给出行动建议。', '请识别最近三天最异常的门店，并说明归因路径。'],
  },
  {
    key: 'stock',
    title: '补货与库存',
    tag: '推荐',
    prompts: ['请分析最近三天爆款断货风险，并给出补货动作清单。', '请给出高风险 SKU 的24小时补货建议。'],
  },
  {
    key: 'shift',
    title: '排班与高峰',
    tag: '推荐',
    prompts: ['请结合排班与高峰客流，给我一个本周的人力优化建议。', '请识别高峰时段的岗位瓶颈并给出调整方案。'],
  },
  {
    key: 'growth',
    title: '经营提升',
    tag: '经营',
    prompts: ['请从品类结构角度给出提升客单价的可执行动作。', '请给出门店连带率提升的本周行动清单。'],
  },
];

const DeepChatPage: React.FC = () => {
  const screens = Grid.useBreakpoint();
  const navigate = useNavigate();
  const { initialState } = useModel('@@initialState');
  const [screenView, setScreenView] = useState<ScreenViewKey>('store');
  const [sessions, setSessions] = useState<SessionMeta[]>([]);
  const [activeSessionId, setActiveSessionId] = useState<string>('');
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const [loadingSessions, setLoadingSessions] = useState(false);
  const [loadingMessages, setLoadingMessages] = useState(false);
  const [promptDrawerOpen, setPromptDrawerOpen] = useState(false);

  const defaultScreenViewFromUser = useMemo<ScreenViewKey>(() => {
    const userRole = initialState?.currentUser?.userRole;
    if (userRole === 'admin') return 'hq';
    return 'store';
  }, [initialState?.currentUser?.userRole]);

  useEffect(() => {
    const cached = localStorage.getItem(BI_OP_VIEW_STORAGE_KEY);
    if (cached === 'hq' || cached === 'store' || cached === 'region') {
      setScreenView(cached);
      return;
    }
    setScreenView(defaultScreenViewFromUser);
  }, [defaultScreenViewFromUser]);

  useEffect(() => {
    const syncFromStorage = () => {
      const cached = localStorage.getItem(BI_OP_VIEW_STORAGE_KEY);
      if (cached === 'hq' || cached === 'store' || cached === 'region') {
        setScreenView(cached);
      }
    };
    window.addEventListener('storage', syncFromStorage);
    return () => {
      window.removeEventListener('storage', syncFromStorage);
    };
  }, []);

  const roleByScreenView: Record<ScreenViewKey, BizRole> = {
    hq: 'manager',
    store: 'operator',
    region: 'operator',
  };

  const screenViewLabel: Record<ScreenViewKey, string> = {
    hq: '总部视角',
    store: '门店视角',
    region: '区域视角',
  };

  const currentSession = useMemo(
    () => sessions.find((item) => item.sessionId === activeSessionId),
    [activeSessionId, sessions],
  );

  const sessionMessagesToday = useMemo(() => {
    const today = new Date();
    return messages.filter((item) => {
      const t = new Date(item.ts);
      return t.getFullYear() === today.getFullYear() && t.getMonth() === today.getMonth() && t.getDate() === today.getDate();
    }).length;
  }, [messages]);

  const groupedSessions = useMemo(() => {
    const today: SessionMeta[] = [];
    const earlier: SessionMeta[] = [];
    const now = Date.now();
    sessions.forEach((item) => {
      const diff = now - item.updatedAt;
      if (diff <= 24 * 60 * 60 * 1000) {
        today.push(item);
      } else {
        earlier.push(item);
      }
    });
    return [
      { key: 'today', label: '今天', data: today },
      { key: 'earlier', label: '更早', data: earlier },
    ];
  }, [sessions]);

  const saveLocal = (nextSessions: SessionMeta[], nextMessagesMap: Record<string, ChatMessage[]>) => {
    localStorage.setItem(LOCAL_SESSIONS_KEY, JSON.stringify(nextSessions));
    localStorage.setItem(LOCAL_MESSAGES_KEY, JSON.stringify(nextMessagesMap));
  };

  const loadLocalData = () => {
    const localSessions = JSON.parse(localStorage.getItem(LOCAL_SESSIONS_KEY) || '[]') as SessionMeta[];
    const localMessages = JSON.parse(localStorage.getItem(LOCAL_MESSAGES_KEY) || '{}') as Record<string, ChatMessage[]>;
    const sortedSessions = [...localSessions].sort((a, b) => b.updatedAt - a.updatedAt);
    setSessions(sortedSessions);
    if (sortedSessions.length > 0) {
      const sid = sortedSessions[0].sessionId;
      setActiveSessionId(sid);
      setMessages(localMessages[sid] || []);
    }
  };

  const loadSessions = async () => {
    setLoadingSessions(true);
    try {
      const res = await listChatSessionsUsingGET();
      const data = (res?.data || []).map((item) => ({
        sessionId: String(item.sessionId || ''),
        title: String(item.title || '新会话'),
        updatedAt: Number(item.updatedAt || Date.now()),
      }));
      if (!data.length) {
        loadLocalData();
        return;
      }
      const sorted = data.sort((a, b) => b.updatedAt - a.updatedAt);
      setSessions(sorted);
      if (!activeSessionId && sorted[0]) {
        setActiveSessionId(sorted[0].sessionId);
      }
    } catch {
      loadLocalData();
    } finally {
      setLoadingSessions(false);
    }
  };

  const loadMessages = async (sessionId: string) => {
    if (!sessionId) return;
    setLoadingMessages(true);
    try {
      const res = await listChatSessionMessagesUsingGET(sessionId);
      const remoteMessages = (res?.data || []).map((item) => ({
        role: item.role === 'assistant' ? 'assistant' : 'user',
        content: String(item.content || ''),
        ts: Number(item.ts || Date.now()),
        actionItems: (item.actionItems || []) as ActionItem[],
      })) as ChatMessage[];
      if (remoteMessages.length) {
        setMessages(remoteMessages);
        return;
      }
      const localMessages = JSON.parse(localStorage.getItem(LOCAL_MESSAGES_KEY) || '{}') as Record<string, ChatMessage[]>;
      setMessages(localMessages[sessionId] || []);
    } catch {
      const localMessages = JSON.parse(localStorage.getItem(LOCAL_MESSAGES_KEY) || '{}') as Record<string, ChatMessage[]>;
      setMessages(localMessages[sessionId] || []);
    } finally {
      setLoadingMessages(false);
    }
  };

  useEffect(() => {
    loadSessions();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (activeSessionId) {
      loadMessages(activeSessionId);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeSessionId]);

  const ensureSession = async () => {
    if (activeSessionId) return activeSessionId;
    const fallbackId = `local_${Date.now()}`;
    try {
      const res = await createChatSessionUsingPOST({ title: '新会话' });
      const sid = String(res?.data?.sessionId || fallbackId);
      const nextSessions = [{ sessionId: sid, title: String(res?.data?.title || '新会话'), updatedAt: Date.now() }, ...sessions];
      setSessions(nextSessions);
      setActiveSessionId(sid);
      return sid;
    } catch {
      const nextSessions = [{ sessionId: fallbackId, title: '新会话', updatedAt: Date.now() }, ...sessions];
      setSessions(nextSessions);
      setActiveSessionId(fallbackId);
      return fallbackId;
    }
  };

  const saveMessageToLocal = (sessionId: string, nextMessages: ChatMessage[]) => {
    const localSessions = [...sessions];
    const localMessages = JSON.parse(localStorage.getItem(LOCAL_MESSAGES_KEY) || '{}') as Record<string, ChatMessage[]>;
    localMessages[sessionId] = nextMessages;
    const title = nextMessages.find((item) => item.role === 'user')?.content?.slice(0, 16) || '新会话';
    const updatedSessions = localSessions.map((item) =>
      item.sessionId === sessionId ? { ...item, title: title.length > 16 ? `${title}...` : title, updatedAt: Date.now() } : item,
    );
    const sorted = updatedSessions.sort((a, b) => b.updatedAt - a.updatedAt);
    setSessions(sorted);
    saveLocal(sorted, localMessages);
  };

  const sendMessage = async (content?: string) => {
    const question = (content ?? input).trim();
    if (!question || sending) return;
    setSending(true);
    try {
      const sid = await ensureSession();
      const userMessage: ChatMessage = { role: 'user', content: question, ts: Date.now() };
      const thinkingMessage: ChatMessage = {
        role: 'assistant',
        content: 'AI 正在思考中，请稍候...',
        ts: Date.now() + 1,
        thinking: true,
      };
      const nextForUI = [...messages, userMessage, thinkingMessage];
      setMessages(nextForUI);
      setInput('');
      const history = nextForUI.slice(-8).map((item) => ({
        role: item.role,
        content: item.content,
      }));
      const res = await deepChatUsingPOST({
        role: roleByScreenView[screenView],
        question,
        history,
        memory: {
          screenView,
          screenViewLabel: screenViewLabel[screenView],
        },
        sessionId: sid,
      });
      const aiData = res?.data;
      const assistantMessage: ChatMessage = {
        role: 'assistant',
        content: aiData?.answer || '当前未获取到有效回答，请稍后重试。',
        ts: Date.now(),
        actionItems: aiData?.actionItems || [],
      };
      const finalMessages = [...messages, userMessage, assistantMessage];
      setMessages(finalMessages);
      saveMessageToLocal(sid, finalMessages);
    } catch (e: any) {
      message.error(`发送失败：${e?.message || '请稍后重试'}`);
    } finally {
      setSending(false);
    }
  };

  const newSession = async () => {
    try {
      const res = await createChatSessionUsingPOST({ title: '新会话' });
      const sid = String(res?.data?.sessionId || `local_${Date.now()}`);
      const title = String(res?.data?.title || '新会话');
      const nextSessions = [{ sessionId: sid, title, updatedAt: Date.now() }, ...sessions];
      setSessions(nextSessions);
      setActiveSessionId(sid);
      setMessages([]);
    } catch {
      const sid = `local_${Date.now()}`;
      const nextSessions = [{ sessionId: sid, title: '新会话', updatedAt: Date.now() }, ...sessions];
      setSessions(nextSessions);
      setActiveSessionId(sid);
      setMessages([]);
    }
  };

  const copyActionToReview = async (item: ActionItem) => {
    const text = `${item.priority || 'P1'} | ${item.title || ''} | 负责人：${item.owner || '待分配'} | 收益：${
      item.expectedBenefit || '-'
    } | 时限：${item.eta || '-'}`;
    try {
      await navigator.clipboard.writeText(text);
      const drafts = JSON.parse(localStorage.getItem(LOCAL_ACTION_DRAFTS_KEY) || '[]') as Array<Record<string, any>>;
      drafts.unshift({ ...item, from: 'deep-chat', createdAt: Date.now() });
      localStorage.setItem(LOCAL_ACTION_DRAFTS_KEY, JSON.stringify(drafts.slice(0, 30)));
      message.success('行动项已复制，并写入回看草稿');
    } catch {
      message.warning('复制失败，请手动复制');
    }
  };

  const getSessionPreview = (sessionId: string) => {
    if (sessionId === activeSessionId && messages.length) {
      const last = [...messages].reverse().find((item) => item.role === 'assistant' || item.role === 'user');
      if (last?.content) return last.content;
    }
    const localMessages = JSON.parse(localStorage.getItem(LOCAL_MESSAGES_KEY) || '{}') as Record<string, ChatMessage[]>;
    const target = localMessages[sessionId] || [];
    const last = [...target].reverse().find((item) => item.content);
    return last?.content || '暂无消息';
  };

  const formatSessionTime = (ts: number) => {
    const d = new Date(ts);
    const month = `${d.getMonth() + 1}`.padStart(2, '0');
    const day = `${d.getDate()}`.padStart(2, '0');
    const hour = `${d.getHours()}`.padStart(2, '0');
    const minute = `${d.getMinutes()}`.padStart(2, '0');
    return `${month}-${day} ${hour}:${minute}`;
  };

  const getPriorityColor = (priority?: string) => {
    if (priority === 'P0') return '#ff4d4f';
    if (priority === 'P1') return '#fa8c16';
    return '#1677ff';
  };

  const insertPromptToInput = (text: string) => {
    setInput(text);
    if (!screens.xl) {
      setPromptDrawerOpen(false);
    }
  };

  const promptPanel = (
    <Card className={screens.xl ? 'deep-chat-card' : ''} title="场景模板中心">
      <div className="prompt-panel-scroll">
      <Space direction="vertical" style={{ width: '100%' }} size={12}>
        {quickPromptGroups.map((group) => (
          <Card key={group.key} size="small" className="prompt-group-card">
            <Space direction="vertical" style={{ width: '100%' }} size={8}>
              <Space>
                <Typography.Text strong>{group.title}</Typography.Text>
                <Tag color="blue">{group.tag}</Tag>
              </Space>
              {group.prompts.map((text) => (
                <Card key={text} size="small" className="prompt-item-card">
                  <Typography.Paragraph style={{ marginBottom: 8 }} ellipsis={{ rows: 2, tooltip: text }}>
                    {text}
                  </Typography.Paragraph>
                  <Space>
                    <Button size="small" onClick={() => insertPromptToInput(text)}>
                      插入输入框
                    </Button>
                    <Button size="small" type="primary" onClick={() => sendMessage(text)}>
                      直接发送
                    </Button>
                  </Space>
                </Card>
              ))}
            </Space>
          </Card>
        ))}
      </Space>
      </div>
    </Card>
  );

  return (
    <div className="deep-chat-page">
      <Row gutter={[12, 12]}>
        <Col xs={24} xl={5}>
          <Card
            className="deep-chat-card"
            title="会话历史"
            loading={loadingSessions}
            extra={
              <Button type="link" onClick={newSession}>
                新建
              </Button>
            }
          >
            <Space direction="vertical" style={{ width: '100%' }} size={8}>
              {groupedSessions.map((group) => (
                <div key={group.key} className="session-group-block">
                  <Typography.Text type="secondary">{group.label}</Typography.Text>
                  <div className="session-group-scroll">
                    <List
                      dataSource={group.data}
                      locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无数据" /> }}
                      renderItem={(item) => {
                        const active = item.sessionId === activeSessionId;
                        return (
                          <List.Item className={`session-item ${active ? 'session-item-active' : ''}`} onClick={() => setActiveSessionId(item.sessionId)}>
                            <Space direction="vertical" size={2} style={{ width: '100%' }}>
                              <Typography.Text strong={active} ellipsis>
                                {item.title}
                              </Typography.Text>
                              <Typography.Text type="secondary" ellipsis style={{ fontSize: 12 }}>
                                {getSessionPreview(item.sessionId)}
                              </Typography.Text>
                              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                                {group.key === 'today' ? new Date(item.updatedAt).toLocaleString() : formatSessionTime(item.updatedAt)}
                              </Typography.Text>
                            </Space>
                          </List.Item>
                        );
                      }}
                    />
                  </div>
                </div>
              ))}
            </Space>
          </Card>
        </Col>

        <Col xs={24} xl={14}>
          <Card
            className="deep-chat-card"
            title={
              <Space>
                <Typography.Text strong>深度对话</Typography.Text>
                <Tag color="processing">经营驾驶舱</Tag>
              </Space>
            }
            extra={
              <Space>
                {!screens.xl ? (
                  <Button onClick={() => setPromptDrawerOpen(true)}>模板中心</Button>
                ) : null}
                <Select<ScreenViewKey>
                  value={screenView}
                  style={{ width: 140 }}
                  options={[
                    { label: '总部视角', value: 'hq' },
                    { label: '门店视角', value: 'store' },
                    { label: '区域视角', value: 'region' },
                  ]}
                  onChange={(v) => {
                    setScreenView(v);
                    localStorage.setItem(BI_OP_VIEW_STORAGE_KEY, v);
                  }}
                />
              </Space>
            }
          >
            <div className="cockpit-header">
              <Space split={<Divider type="vertical" />}>
                <Badge color="#1677ff" text={`当前视角：${screenViewLabel[screenView]}`} />
                <Badge color="#1677ff" text={`当前会话：${currentSession?.title || '未命名会话'}`} />
                <Typography.Text type="secondary">今日轮次：{sessionMessagesToday}</Typography.Text>
                <Typography.Text type="secondary">
                  最近更新：{messages.length ? new Date(messages[messages.length - 1].ts).toLocaleString() : '-'}
                </Typography.Text>
              </Space>
            </div>

            <div className="message-viewport">
              {!messages.length ? (
                <Empty description="输入业务问题开始分析" />
              ) : (
                <Space direction="vertical" style={{ width: '100%' }} size={10}>
                  {messages.map((item, index) => (
                    <Card key={`${item.ts}-${index}`} size="small" loading={loadingMessages} className={item.role === 'assistant' ? 'msg-card-assistant' : 'msg-card-user'}>
                      <Typography.Text strong>{item.role === 'assistant' ? (item.thinking ? 'AI思考中' : 'AI洞察') : '你的问题'}</Typography.Text>
                      <Typography.Paragraph style={{ marginTop: 6, marginBottom: 0 }}>{item.content}</Typography.Paragraph>
                      {item.role === 'assistant' && !item.thinking && item.actionItems?.length ? (
                        <Space direction="vertical" style={{ marginTop: 10, width: '100%' }}>
                          {item.actionItems.map((action, actionIndex) => (
                            <Card key={`${action.title}-${actionIndex}`} size="small" className="action-item-card">
                              <div className="action-item-row">
                                <div className="action-priority-bar" style={{ backgroundColor: getPriorityColor(action.priority) }} />
                                <div style={{ flex: 1 }}>
                                  <Space>
                                    <Tag color={action.priority === 'P0' ? 'error' : action.priority === 'P1' ? 'warning' : 'processing'}>
                                      {action.priority || 'P1'}
                                    </Tag>
                                    <Typography.Text strong>{action.title || '待补充行动项'}</Typography.Text>
                                  </Space>
                                  <div>
                                    <Typography.Text type="secondary">收益：{action.expectedBenefit || '-'}</Typography.Text>
                                  </div>
                                  <div>
                                    <Typography.Text type="secondary">负责人：{action.owner || '待分配'}</Typography.Text>
                                    <Typography.Text type="secondary" style={{ marginLeft: 12 }}>
                                      时限：{action.eta || '-'}
                                    </Typography.Text>
                                  </div>
                                  <Space style={{ marginTop: 6 }}>
                                    <Button size="small" onClick={() => copyActionToReview(action)}>
                                      复制到回看
                                    </Button>
                                    <Button size="small" type="link" onClick={() => navigate('/my_chart')}>
                                      前往结果回看
                                    </Button>
                                  </Space>
                                </div>
                              </div>
                            </Card>
                          ))}
                        </Space>
                      ) : null}
                    </Card>
                  ))}
                </Space>
              )}
            </div>

            <div className="input-sticky-area">
              <div className="deep-chat-input-compact">
                <Input.TextArea
                  value={input}
                  placeholder="请输入问题：例如“门店A今日销售下滑，最可能原因及优先动作？”"
                  autoSize={{ minRows: 2, maxRows: 4 }}
                  onChange={(e) => setInput(e.target.value)}
                  onPressEnter={(e) => {
                    if (!e.shiftKey) {
                      e.preventDefault();
                      sendMessage();
                    }
                  }}
                />
                <Button className="deep-chat-send-btn" type="primary" loading={sending} onClick={() => sendMessage()}>
                  {sending ? '思考中...' : '发送'}
                </Button>
              </div>
            </div>
          </Card>
        </Col>

        <Col xs={24} xl={5}>
          {screens.xl ? promptPanel : null}
        </Col>
      </Row>

      <Drawer title="场景模板中心" open={promptDrawerOpen} width={420} onClose={() => setPromptDrawerOpen(false)}>
        {promptPanel}
      </Drawer>
    </div>
  );
};

export default DeepChatPage;
