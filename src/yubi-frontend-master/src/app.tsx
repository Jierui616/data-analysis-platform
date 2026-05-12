import { Question } from '@/components/RightContent';
import { getLoginUserUsingGET } from '@/services/yubi/userController';
import defaultSettings from '../config/defaultSettings';
import { LinkOutlined, LogoutOutlined } from '@ant-design/icons';
import { SettingDrawer } from '@ant-design/pro-components';
import type { RunTimeLayoutConfig } from '@umijs/max';
import { history, Link } from '@umijs/max';
import { Button } from 'antd';
import { AvatarDropdown, AvatarName } from './components/RightContent/AvatarDropdown';
import { errorConfig } from './requestConfig';

const isDev = process.env.NODE_ENV === 'development';
const loginPath = '/user/login';

/**
 * @see  https://umijs.org/zh-CN/plugins/plugin-initial-state
 * */
export async function getInitialState(): Promise<{
  currentUser?: API.LoginUserVO;
}> {
  const fetchUserInfo = async () => {
    try {
      const res = await getLoginUserUsingGET();
      return res.data;
    } catch (error) {
      history.push(loginPath);
    }
    return undefined;
  };
  // 如果不是登录页面，执行
  const { location } = history;
  if (location.pathname !== loginPath) {
    const currentUser = await fetchUserInfo();
    return {
      currentUser,
    };
  }

  return {};
}

// ProLayout 支持的api https://procomponents.ant.design/components/layout
export const layout: RunTimeLayoutConfig = ({ initialState, setInitialState }) => {
  return {
    actionsRender: () => [
      <Question key="doc" />,
      <Button
        key="logout-to-login"
        type="link"
        size="small"
        icon={<LogoutOutlined />}
        onClick={() => {
          setInitialState((pre) => ({
            ...pre,
            currentUser: undefined,
          }));
          history.push(loginPath);
        }}
      >
        返回登录
      </Button>,
    ],
    avatarProps: {
      src: initialState?.currentUser?.userAvatar,
      title: <AvatarName />,
      render: (_, avatarChildren) => {
        return <AvatarDropdown>{avatarChildren}</AvatarDropdown>;
      },
    },
    waterMarkProps: {
      content: initialState?.currentUser?.userName || initialState?.currentUser?.userAccount,
    },
    footerRender: false,
    onPageChange: () => {
      const { location } = history;
      // 如果没有登录，重定向到 login
      if (!initialState?.currentUser && location.pathname !== loginPath) {
        history.push(loginPath);
      }
    },
    /** 演示/投影下去除装饰底图，内容区更干净 */
    layoutBgImgList: [],
    links: isDev
      ? [
          <Link key="openapi" to="/umi/plugin/openapi" target="_blank">
            <LinkOutlined />
            <span>OpenAPI 文档</span>
          </Link>,
        ]
      : [],
    menuHeaderRender: undefined,
    // 自定义 403 页面
    // unAccessible: <div>unAccessible</div>,
    // 增加一个 loading 的状态
    childrenRender: (children) => {
      return (
        <>
          {children}
          {isDev ? (
            <SettingDrawer
              disableUrlParams
              enableDarkTheme
              settings={initialState?.settings}
              onSettingChange={(settings) => {
                setInitialState((preInitialState) => ({
                  ...preInitialState,
                  settings,
                }));
              }}
            />
          ) : null}
        </>
      );
    },
    ...initialState?.settings,
    /** SettingDrawer / 历史缓存里的 settings 会覆盖 layout，侧栏 Logo 需固定为项目资源 */
    logo: defaultSettings.logo,
    title: defaultSettings.title,
  };
};

/**
 * @name request 配置，可以配置错误处理
 * 它基于 axios 和 ahooks 的 useRequest 提供了一套统一的网络请求和错误处理方案。
 * @doc https://umijs.org/docs/max/request#配置
 */
export const request = {
  // 后端运行端口可能因本机占用而变化；这里与当前后端对齐
  baseURL: 'http://localhost:18080',
  withCredentials: true,
  ...errorConfig,
};
