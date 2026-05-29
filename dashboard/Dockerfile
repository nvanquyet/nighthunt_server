FROM node:18-alpine

WORKDIR /app

# Install Python3 for load test runner + Docker CLI for JMeter runner
RUN apk add --no-cache python3 py3-requests docker-cli

# Copy package files
COPY package*.json ./

# Install dependencies
RUN npm install

# Copy source
COPY . .

# Expose port
EXPOSE 3000

# Start
CMD ["npm", "start"]

