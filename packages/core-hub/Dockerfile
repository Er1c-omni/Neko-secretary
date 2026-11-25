FROM node:24-alpine

WORKDIR /app

RUN corepack enable

COPY package.json pnpm-workspace.yaml tsconfig.base.json ./
COPY packages/protocol/package.json packages/protocol/
COPY packages/core-hub/package.json packages/core-hub/

RUN pnpm install --prod --frozen-lockfile || pnpm install --prod

COPY packages/protocol packages/protocol
COPY packages/core-hub packages/core-hub

ENV HOST=0.0.0.0
ENV PORT=8787
ENV DB_PATH=/app/data/neko.db

EXPOSE 8787

CMD ["node", "packages/core-hub/src/index.ts"]
