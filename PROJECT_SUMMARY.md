# Sparkboard Project Summary

## Overview

Sparkboard is a full-stack Clojure/ClojureScript web application designed for managing hackathons and collaborative events. It provides a comprehensive platform for event organizers to manage participants, teams, projects, and community interactions in a real-time, reactive environment.

## Technology Stack

### Languages
- **Backend**: Clojure
- **Frontend**: ClojureScript with React
- **Supporting**: JavaScript/TypeScript for specific components

### Core Technologies
- **re-db**: Custom end-to-end reactive library for streaming data from database to frontend
- **yawn**: React wrapper supporting hiccup syntax with live reloading
- **Datalevin**: Primary database (Datalog-based, similar to Datomic)
- **Shadow-cljs**: ClojureScript build tool
- **HTTP Kit**: Web server
- **Tailwind CSS**: Styling framework
- **Firebase Auth**: Authentication (Google OAuth and email/password)

## Architecture

### Project Structure
```
sparkboard/
├── src/sb/               # Core application code
│   ├── app/             # Business logic modules (board, project, account, etc.)
│   ├── client/          # ClojureScript frontend code
│   ├── server/          # Clojure backend code
│   └── migrate/         # Database migration utilities
├── resources/public/    # Static assets (CSS, JS, images)
├── dev/                 # Development tools and utilities
├── test/               # Test files
└── docs/               # Architecture documentation
```

### Key Architectural Patterns

1. **Full-Stack Reactive Architecture**: Data flows from Datalevin through re-db to React components, enabling real-time updates across all connected clients.

2. **Multi-Tenant SaaS**: Organizations and boards are served from custom subdomains (`<subdomain>.sparkboard.com`).

3. **Component-Based UI**: Modular React components written in ClojureScript using hiccup syntax.

4. **Entity-Based Data Model**: Core entities include Board, Organization, Project, Account, Membership, and Field.

## Core Features

### Event Management
- **Boards**: Represent individual hackathons or events
- **Organizations**: Entities that create and manage multiple boards
- **Dynamic Fields**: Customizable fields for member profiles and project descriptions
- **Access Control**: Open registration or invite-only events

### Team Collaboration
- **Projects**: Teams working together during an event
- **Project Matching**: Help participants find teams based on interests
- **Slack Integration**: Automatic Slack workspace creation for teams
- **Discussion Threads**: Project-specific communication channels

### Member Experience
- **User Profiles**: Customizable member profiles with dynamic fields
- **Social Feed**: Activity stream showing updates and interactions
- **Private Messaging**: Direct communication between participants
- **Notifications**: Configurable notification preferences (instant/periodic/daily)

### Administration
- **Board Administration**: Manage event settings, members, and projects
- **Custom Fields**: Create and manage dynamic fields for profiles and projects
- **Voting System**: Built-in balloting for project evaluation
- **Analytics**: Track member engagement and project statistics

## Data Model

### Primary Entities
- **Account**: User accounts with authentication details
- **Board**: Individual hackathon events
- **Organization**: Groups that manage boards
- **Project**: Teams and their submissions
- **Membership**: Links accounts to boards with roles
- **Field**: Dynamic, customizable data fields
- **Discussion/Chat**: Communication threads
- **Notification**: User notification preferences and history

### Database Strategy
- Primary storage in Datalevin (Datalog database)
- Migration path from legacy Firebase/MongoDB systems
- Real-time synchronization via WebSockets

## Development Workflow

### Getting Started
```bash
bb dev          # Start development server with hot reloading
bb repl         # Start REPL for interactive development
bb test         # Run test suite
```

### Key Development Features
- REPL-driven development
- Hot code reloading for both frontend and backend
- Component isolation for UI development
- Comprehensive test suite

## Deployment

- **Hosting**: Fly.io (staging and production environments)
- **Containerization**: Docker-based deployment
- **CI/CD**: GitHub Actions for automated testing and deployment
- **Environments**: Separate staging and production configurations

## API Structure

### REST Endpoints
- `/api/board/*` - Board management
- `/api/project/*` - Project operations
- `/api/account/*` - User account management
- `/api/org/*` - Organization administration

### WebSocket Communication
- Real-time data synchronization
- Live notifications
- Presence tracking

## Security

- Firebase Authentication for user management
- JWT tokens for API authentication
- Role-based access control (admin, member, viewer)
- Secure WebSocket connections for real-time features

## Notable Design Decisions

1. **Unified Language**: Using Clojure/ClojureScript across the stack reduces context switching and enables code sharing.

2. **Reactive Architecture**: The re-db library enables automatic UI updates when data changes, simplifying state management.

3. **Extensible Fields**: Dynamic field system allows events to customize data collection without code changes.

4. **Multi-Tenant Architecture**: Subdomain-based routing enables branded experiences for different organizations.

## Future Considerations

- Complete migration from Firebase/MongoDB to Datalevin
- Enhanced analytics and reporting features
- Mobile application development
- Extended integration capabilities

## Development Tips for LLMs

1. **Namespace Convention**: Follow `sb.module.submodule` naming pattern
2. **Hiccup Syntax**: UI components use Clojure's hiccup format `[:div {:class "..."} content]`
3. **Reactive Patterns**: Use re-db subscriptions for data access in components
4. **REPL First**: Test functions and ideas in the REPL before implementing
5. **Component Structure**: Keep UI components small and focused on single responsibilities

## Excluded Directories
- `src/org/` - Legacy code not part of the current application
- `cloudflare-mailer/` - Discontinued email service

This summary provides a comprehensive introduction to the Sparkboard codebase, its architecture, and key concepts for effective development and maintenance.